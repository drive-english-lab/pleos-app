package com.example.pleosapp

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        content.addView(TextView(this).apply {
            text = "Pleos 앱 개발 시작점"
            textSize = 28f
        })
        content.addView(TextView(this).apply {
            text = "Android 프로젝트와 SDK 환경을 준비하고 있습니다.\n앱 아이디어와 사용할 수 있는 Pleos API를 정한 뒤 첫 기능을 붙입니다."
            textSize = 16f
            setPadding(0, dp(16), 0, 0)
        })

        setContentView(content)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
