/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.R
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.unrolled.decoration.FlexboxVerticalDecoration
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.recyclerview.recyclerView
import timber.log.Timber
import kotlin.math.max

class CompactCandidateDelegate : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val context: Context by di.instance()
    val service: TrimeInputMethodService by di.instance()
    val rime: RimeSession by di.instance()
    val theme: Theme by di.instance()
    private val inputView: InputView by di.instance()
    val bar: InputBarDelegate by di.instance()

    private val fillStyle by AppPrefs.defaultInstance().keyboard.horizontalCandidateMode

    // 巨硬模式：候选词按 2 3 1 4 5 排列，每页 5 个，偏移自维护（不依赖 Rime 翻页）
    private val giantHardMode get() = AppPrefs.defaultInstance().keyboard.giantHardMode.getValue()
    private val giantHardPageSize = 5
    private val giantHardDisplayOrder = intArrayOf(1, 2, 0, 3, 4)
    var giantHardPageOffset = 0
        private set
    private var giantHardAvailable = 0
    private var giantHardFetchJob: Job? = null

    private val maxSpanCountPref by lazy {
        AppPrefs.defaultInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                maxSpanCount
            } else {
                maxSpanCountLandscape
            }
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 0f
    private var layoutFixedWidth = 0

    /**
     * (for [CompactCandidateMode.AUTO_FILL] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false

    private val _unrolledCandidateOffset =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val unrolledCandidateOffset = _unrolledCandidateOffset.asSharedFlow()

    fun refreshUnrolled(childCount: Int) {
        _unrolledCandidateOffset.tryEmit(childCount)
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to
                (adapter.total == childCount),
        )
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesHighlighted to
                (adapter.highlightedIdx >= childCount && !giantHardMode),
        )
    }

    val adapter by lazy {
        CompactCandidateViewAdapter(theme).apply {
            setOnItemClickListener { _, _, position ->
                if (giantHardMode) {
                    val pageLocal = giantHardDisplayOrder.getOrElse(position) { -1 }
                    if (pageLocal in 0 until giantHardAvailable) {
                        val global = giantHardPageOffset + pageLocal
                        rime.launchOnReady { it.selectCandidate(global, global = true) }
                    }
                } else {
                    rime.launchOnReady { it.selectCandidate(position, global = true) }
                }
            }
            setOnItemLongClickListener { _, view, position ->
                if (giantHardMode) {
                    val pageLocal = giantHardDisplayOrder.getOrElse(position) { -1 }
                    if (pageLocal in 0 until giantHardAvailable) {
                        val global = giantHardPageOffset + pageLocal
                        inputView.showCandidateActionMenu(global, items[position].text, view, global = true)
                    }
                } else {
                    inputView.showCandidateActionMenu(position, items[position].text, view, global = true)
                }
                true
            }
        }
    }

    fun updateLayoutParams(minWidth: Int, flexGrow: Float) {
        layoutMinWidth = minWidth
        layoutFlexGrow = flexGrow
    }

    val layoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            override fun canScrollHorizontally(): Boolean = false

            override fun canScrollVertically(): Boolean = false

            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                if (secondLayoutPassNeeded) {
                    if (cnt < adapter.itemCount) {
                        // [^2] RecyclerView can't display all candidates
                        // update LayoutParams in onLayoutCompleted would trigger another
                        // onLayoutCompleted, skip the second one to avoid infinite loop
                        if (secondLayoutPassDone) return
                        secondLayoutPassDone = true
                        for (i in 0 until cnt) {
                            getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                flexGrow = 1f
                            }
                        }
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                refreshUnrolled(cnt)
            }
        }
    }

    private val separatorDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val spacing = theme.generalStyle.candidateSpacing
            val intrinsicSize = max(spacing, context.dp(spacing)).toInt()
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = ColorManager.getColor("candidate_separator_color")
        }
    }

    val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (giantHardMode) {
                    layoutFixedWidth = w / giantHardPageSize - separatorDrawable.intrinsicWidth
                } else if (fillStyle == CompactCandidateMode.AUTO_FILL) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - separatorDrawable.intrinsicWidth
                }
            }
        }
        context.recyclerView(R.id.candidate_view) {
            itemAnimator = null
            adapter = this@CompactCandidateDelegate.adapter
            layoutManager = this@CompactCandidateDelegate.layoutManager
            addItemDecoration(FlexboxVerticalDecoration(separatorDrawable))
        }
    }

    override fun onCandidateListUpdate(data: RimeMessage.CandidateListMessage.Data) {
        val (total, highlighted, candidates) = data
        Timber.d("巨硬 onCandidateListUpdate: total=$total highlighted=$highlighted texts=${candidates.map { it.text }}")

        if (giantHardMode && candidates.isNotEmpty()) {
            // 新组合：回到第一页，按偏移取当前页候选
            giantHardPageOffset = 0
            Timber.d("巨硬 新组合：offset 重置为 0")
            updateGiantHardCandidates()
            return
        }

        giantHardAvailable = 0
        updateLayoutForCandidates(candidates)
        adapter.updateCandidates(candidates, total, highlighted)

        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (candidates.isEmpty()) {
            refreshUnrolled(0)
        }
    }

    /** 巨硬模式翻页：自维护偏移，不依赖 Rime 翻页 */
    fun giantHardNextPage() {
        giantHardPageOffset += giantHardPageSize
        Timber.d("巨硬 翻页：offset=$giantHardPageOffset")
        updateGiantHardCandidates()
    }

    private fun updateGiantHardCandidates() {
        val offset = giantHardPageOffset
        Timber.d("巨硬 取候选：offset=$offset")
        giantHardFetchJob?.cancel()
        giantHardFetchJob =
            service.lifecycleScope.launch {
                val pageCandidates = rime.runOnReady { getCandidates(offset, giantHardPageSize) }
                Timber.d("巨硬 取到候选：offset=$offset texts=${pageCandidates.map { it.text }}")
                val available = pageCandidates.size
                giantHardAvailable = available
                if (available == 0) {
                    updateLayoutForCandidates(emptyArray())
                    adapter.updateCandidates(emptyArray(), 0, -1)
                    refreshUnrolled(0)
                    return@launch
                }
                // 固定 5 个格子：不足 5 个时用空占位，保持候选在原来的格子里
                val display = Array(giantHardPageSize) { pos ->
                    val pageLocal = giantHardDisplayOrder[pos]
                    if (pageLocal < available) {
                        pageCandidates[pageLocal]
                    } else {
                        CandidateProto("", "", "ph$pos")
                    }
                }
                updateLayoutForCandidates(display)
                // 固定高亮中间那个候选（显示顺序 2 3 1 4 5 的中间，即第 1 个候选）
                adapter.updateCandidates(display, giantHardPageSize, giantHardPageSize / 2)
            }
    }

    private fun updateLayoutForCandidates(candidates: Array<CandidateProto>) {
        if (giantHardMode) {
            // 巨硬模式：5 个候选固定宽度平分候选栏，超出自动缩字体
            layoutFixedWidth =
                if (view.width > 0) view.width / giantHardPageSize - separatorDrawable.intrinsicWidth else 0
            layoutMinWidth = 0
            layoutFlexGrow = if (layoutFixedWidth > 0) 0f else 1f
            secondLayoutPassNeeded = false
            secondLayoutPassDone = false
        } else {
            layoutFixedWidth = 0
            val maxSpanCount = maxSpanCountPref.getValue()
            when (fillStyle) {
                CompactCandidateMode.NEVER_FILL -> {
                    layoutMinWidth = 0
                    layoutFlexGrow = 0f
                    secondLayoutPassNeeded = false
                }
                CompactCandidateMode.AUTO_FILL -> {
                    layoutMinWidth = view.width / maxSpanCount - separatorDrawable.intrinsicWidth
                    layoutFlexGrow = if (candidates.size < maxSpanCount) 0f else 1f
                    // [^1] total candidates count < maxSpanCount
                    secondLayoutPassNeeded = candidates.size < maxSpanCount
                    secondLayoutPassDone = false
                }
                CompactCandidateMode.ALWAYS_FILL -> {
                    layoutMinWidth = 0
                    layoutFlexGrow = 1f
                    secondLayoutPassNeeded = false
                }
            }
        }
        adapter.updateLayoutParams(layoutMinWidth, layoutFlexGrow, layoutFixedWidth)
    }
}
