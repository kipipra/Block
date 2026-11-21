package com.focuslock.app

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** A rounded rectangle, optionally with an outline. */
fun roundedDrawable(fill: Int, radiusPx: Int, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(fill)
    g.cornerRadius = radiusPx.toFloat()
    if (strokePx > 0) g.setStroke(strokePx, strokeColor)
    return g
}

/** A rounded rectangle that fades from [top] to [bottom]. */
fun gradientDrawable(top: Int, bottom: Int, radiusPx: Int): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
    g.cornerRadius = radiusPx.toFloat()
    return g
}

/** Wraps a background so touches show a ripple that is clipped to the same rounded shape. */
fun withRipple(content: Drawable, radiusPx: Int, rippleColor: Int = Color.argb(46, 255, 255, 255)): Drawable {
    val mask = GradientDrawable()
    mask.setColor(Color.WHITE)
    mask.cornerRadius = radiusPx.toFloat()
    return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
}

/** Makes a view shrink slightly while pressed and spring back when released. Clicks still work. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable(pressedScale: Float = 0.96f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate()
                .scaleX(pressedScale).scaleY(pressedScale)
                .setDuration(90L).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(240L).setInterpolator(OvershootInterpolator(2.4f)).start()
        }
        false
    }
}
