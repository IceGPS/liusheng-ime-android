package com.icegps.voiceime.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.icegps.voiceime.ime.pinyin.UserDict

/**
 * 个人词典独立管理页（V73）。
 *
 * Hongjie 反馈：设置页内嵌逐条列表，词条多了页面无限变长。
 * 改为独立 Activity；列表用 ListView（view 回收高效滚动）。
 * 注：构建链无 androidx 依赖，故不用 RecyclerView，ListView 回收机制同理。
 */
class UserDictActivity : Activity() {

    private lateinit var countLabel: TextView
    private lateinit var emptyLabel: TextView
    private lateinit var listView: ListView
    private val adapter = WordAdapter()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserDict.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "个人词典"
            textSize = 20f
            setTextColor(Color.BLACK)
        })

        countLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(countLabel)

        emptyLabel = TextView(this).apply {
            text = "（空）"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(32))
            visibility = View.GONE
        }
        root.addView(emptyLabel)

        listView = ListView(this).apply {
            adapter = this@UserDictActivity.adapter
            dividerHeight = 1
        }
        root.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )

        val clearBtn = Button(this).apply {
            text = "清空全部"
            setOnClickListener { confirmClear() }
        }
        root.addView(
            clearBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val words = UserDict.listAll()
        adapter.setData(words)
        countLabel.text = "共 ${words.size} 条"
        emptyLabel.visibility = if (words.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (words.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmClear() {
        val n = UserDict.count()
        if (n == 0) {
            Toast.makeText(this, "已经是空的", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空个人词典")
            .setMessage("确定删除全部 $n 个自学习词条吗？此操作不可恢复。")
            .setPositiveButton("确定") { _, _ ->
                UserDict.clear()
                refresh()
                Toast.makeText(this, "个人词典已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class WordAdapter : BaseAdapter() {
        private var data: List<UserDict.UserWord> = emptyList()

        fun setData(words: List<UserDict.UserWord>) {
            data = words
            notifyDataSetChanged()
        }

        override fun getCount(): Int = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row: LinearLayout
            val holder: Holder
            if (convertView == null) {
                row = LinearLayout(this@UserDictActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                val info = TextView(this@UserDictActivity).apply {
                    textSize = 15f
                    setTextColor(Color.DKGRAY)
                }
                row.addView(
                    info,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                val del = Button(this@UserDictActivity).apply { text = "删除" }
                row.addView(del)
                holder = Holder(info, del)
                row.tag = holder
            } else {
                row = convertView as LinearLayout
                holder = row.tag as Holder
            }
            val uw = data[position]
            holder.info.text = "${uw.word}（${uw.pinyin}）×${uw.freq}"
            holder.del.setOnClickListener {
                UserDict.deleteByWord(uw.word)
                refresh()
                Toast.makeText(this@UserDictActivity, "已删除“${uw.word}”", Toast.LENGTH_SHORT).show()
            }
            return row
        }
    }

    /** 行 View 的缓存 holder（kotlinc 2.0.20 禁止 inner class 内再声明 class，故放 Activity 层级） */
    private class Holder(val info: TextView, val del: Button)
}
