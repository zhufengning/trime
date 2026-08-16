/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.widget.ViewAnimator
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.ime.keyboard.GestureFrame
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import timber.log.Timber

class AlwaysUi(
    override val ctx: Context,
    private val theme: Theme,
    private val onButtonClick: ((String) -> Unit)? = null,
    private val onCursorSlide: ((Int) -> Unit)? = null,
) : Ui {
    enum class State {
        Toolbar,
        Clipboard,
        InlineSuggestion,
    }

    var currentState = State.Toolbar
        private set

    private fun toolButton(
        buttonConfig: ToolBar.Button?,
        @DrawableRes icon: Int = 0,
    ): ToolButton = if (buttonConfig != null) {
        ToolButton(ctx, buttonConfig).apply {
            setOnClickListener { onButtonClick?.invoke(buttonConfig.action) }
            val longPressAction = buttonConfig.longPressAction
            if (longPressAction.isNotEmpty()) {
                setOnLongClickListener {
                    onButtonClick?.invoke(longPressAction)
                    true
                }
            }
        }
    } else {
        ToolButton(ctx, icon).apply {
            setOnClickListener { onButtonClick?.invoke("") }
        }
    }

    val buttonsUi = ButtonsBarUi(ctx, theme, onButtonClick)

    val clipboardUi = ClipboardSuggestionUi(ctx)

    val inlineSuggestionsUi = InlineSuggestionsUi(ctx)

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_drop_down_24)
    private val rightMostButton =
        ViewAnimator(ctx).apply {
            add(hideKeyboardButton, lParams(matchParent, matchParent))
            buttonsUi.firstButton?.let { add(it, lParams(matchParent, matchParent)) }
        }

    private val leftMostButton = toolButton(
        theme.toolBar.primaryButton,
        R.drawable.ic_baseline_more_horiz_24,
    )

    private val animator =
        ViewAnimator(ctx).apply {
            add(buttonsUi.root, lParams(matchParent, matchParent))
            add(clipboardUi.root, lParams(matchParent, matchParent))
            add(inlineSuggestionsUi.root, lParams(matchParent, matchParent))
        }

    private val content: ConstraintLayout = constraintLayout {
        val (leftWidth, leftHeight) = buttonsUi.getButtonSize(theme.toolBar.primaryButton)
        val (rightWidth, rightHeight) = buttonsUi.getButtonSize(theme.toolBar.buttons.firstOrNull())

        add(
            leftMostButton,
            lParams(leftWidth, leftHeight) {
                startOfParent()
                centerVertically()
            },
        )
        add(
            rightMostButton,
            lParams(rightWidth, rightHeight) {
                endOfParent()
                centerVertically()
            },
        )
        add(
            animator,
            lParams(matchConstraints, matchParent) {
                after(leftMostButton)
                before(rightMostButton)
                endOfParent()
                centerVertically()
            },
        )
    }.apply {
        updateRightMostButton(State.Toolbar)
    }

    override val root = view(::GestureFrame) {
        add(content, lParams(matchParent, matchParent))
    }.apply {
        isSlideCursor = true
        interceptSlide = true
        onSlide = { delta, _, _ ->
            onCursorSlide?.invoke(delta)
        }
    }

    fun updateButtonsStyle(option: String, enabled: Boolean) {
        leftMostButton.updateStyle(option, enabled)
        buttonsUi.firstButton?.updateStyle(option, enabled)
        buttonsUi.updateStyle(option, enabled)
    }

    fun toggleOptions(): Set<String> = buildSet {
        leftMostButton.option?.let { add(it) }
        buttonsUi.firstButton?.option?.let { add(it) }
        buttonsUi.root.children.forEach { (it as ToolButton).option?.let { add(it) } }
    }

    fun updateState(state: State) {
        Timber.d("Switch always ui to $state")
        animator.displayedChild = state.ordinal
        currentState = state
        updateRightMostButton(state)
        updateLeftMostButton(state)
    }

    private fun updateRightMostButton(state: State) {
        val hasFirstButton = buttonsUi.firstButton != null
        val showFirst = hasFirstButton && (theme.toolBar.buttons.isNotEmpty() || state != State.Toolbar)
        rightMostButton.displayedChild = if (showFirst) 1 else 0
    }

    private fun updateLeftMostButton(state: State) {
        val buttonConfig =
            if (state == State.Toolbar) {
                theme.toolBar.primaryButton
            } else {
                theme.toolBar.buttons.firstOrNull()
            }

        val (buttonWidth, buttonHeight) = buttonsUi.getButtonSize(buttonConfig)
        leftMostButton.layoutParams = leftMostButton.layoutParams.apply {
            width = buttonWidth
            height = buttonHeight
        }
    }
}
