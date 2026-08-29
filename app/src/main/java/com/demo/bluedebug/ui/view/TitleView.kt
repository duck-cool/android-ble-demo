package com.demo.bluedebug.ui.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.marginLeft
import com.demo.bluedebug.R

class TitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
): RelativeLayout(context,attrs,defStyleAttr){

    private lateinit var ivBack: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var ivMenu: ImageView


    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.TitleView, defStyleAttr, 0)
        val titleText = typedArray.getString(R.styleable.TitleView_titleText) ?: "页面标题"
        val titleTextSize = typedArray.getDimension(R.styleable.TitleView_titleTextSize, 18f)
        val titleTextColor = typedArray.getColor(R.styleable.TitleView_titleTextColor, Color.BLACK)
        val barBgColor = typedArray.getColor(R.styleable.TitleView_barBackgroundColor, Color.WHITE)
        val buttonSize = typedArray.getDimension(R.styleable.TitleView_buttonSize, 24f)
        typedArray.recycle()

        post {
            Log.i("PZP", "属性: [titleText: $titleText, titleTextSize:$titleTextSize, titleTextColor: $titleTextColor, height: $height, density: ${resources.displayMetrics.density}")
        }

        setBackgroundColor(barBgColor)

        initBackButton(buttonSize)
        initMenuButton(buttonSize)
        initTitle(titleText,titleTextSize,titleTextColor)

    }

    /**
     * 初始化左侧返回按钮
     */
    private fun initBackButton(buttonSize: Float){
        ivBack = ImageView(context).apply {
            id = generateViewId()
            setImageResource(R.drawable.ic_back)
        }

        val params = LayoutParams(buttonSize.toInt(), buttonSize.toInt()).apply {
            addRule(ALIGN_PARENT_START,TRUE)
            addRule(CENTER_VERTICAL,TRUE)
            marginStart = 32
        }

        addView(ivBack,params)
    }


    /**
     * 初始化中间标题文字
     */
    private fun initTitle(titleText: String, titleTextSize: Float, titleTextColor: Int){
        tvTitle = TextView(context).apply {
            id = generateViewId()
            setTextSize(TypedValue.COMPLEX_UNIT_PX,titleTextSize)
            text = titleText
            setTextColor(titleTextColor)
            gravity = Gravity.CENTER
            maxLines = 1
        }

        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            addRule(CENTER_IN_PARENT, TRUE)
            addRule(END_OF,ivBack.id)
            addRule(START_OF,ivMenu.id)
        }

        addView(tvTitle,params)
    }


    /**
     * 初始化右侧菜单按钮
     */
    private fun initMenuButton(buttonSize: Float){
        ivMenu = ImageView(context).apply {
            id = generateViewId()
            setImageResource(R.drawable.ic_menu)
        }
        val params = LayoutParams(buttonSize.toInt(), buttonSize.toInt()).apply {
            addRule(ALIGN_PARENT_END,TRUE)
            addRule(CENTER_VERTICAL,TRUE)
            marginEnd = 32
        }


        addView(ivMenu,params)
    }

    // ================== 对外暴露的 API ==================

    /** 设置标题文本 */

    fun setTitle(title: String) {
        tvTitle.text = title
    }


    /** 设置左侧返回按钮点击监听 */

    fun setOnBackClickListener(listener: OnClickListener?) {
        ivBack.setOnClickListener(listener)
    }


    /** 设置右侧菜单按钮点击监听 */

    fun setOnMenuClickListener(listener: OnClickListener?) {
        ivMenu.setOnClickListener(listener)

    }

    /** dp 转 px */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()

    }
}