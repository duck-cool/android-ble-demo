package com.demo.bluedebug.ui

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.demo.bluedebug.R
import com.demo.bluedebug.ui.view.TitleView

abstract class BaseActivity: AppCompatActivity() {

    protected lateinit var titleView: TitleView

    override fun setContentView(layoutResID: Int) {
        super.setContentView(R.layout.activity_base)

        titleView = findViewById<TitleView>(R.id.titleTv)
        val contentContainer = findViewById<FrameLayout>(R.id.base_content_container)

        LayoutInflater.from(this).inflate(layoutResID, contentContainer, true)

    }
}