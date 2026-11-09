package com.family.curated

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.curated.data.Item
import com.family.curated.data.KioskKeys
import com.family.curated.data.Repo
import com.family.curated.data.Store
import com.family.curated.databinding.ActivityEpisodesBinding

class EpisodesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_TITLE = "title"
        const val EXTRA_GATE = "gate"
    }

    private lateinit var b: ActivityEpisodesBinding
    private var folder: String = ""
    private var episodes: List<Item> = emptyList()
    private var seekLock = false
    private var loadFailed = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (KioskKeys.systemBlocked(keyCode)) return true
        // 读失败或读空时列表里没有焦点，遥控器没得按：按 OK 直接重试
        if ((loadFailed || episodes.isEmpty()) &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            loadEpisodes()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        b = ActivityEpisodesBinding.inflate(layoutInflater)
        setContentView(b.root)

        folder = intent.getStringExtra(EXTRA_FOLDER) ?: ""
        seekLock = intent.getBooleanExtra(EXTRA_GATE, false)
        b.tvTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: ""

        b.rvEpisodes.layoutManager = LinearLayoutManager(this)
        loadEpisodes()
    }

    private fun loadEpisodes() {
        b.tvSub.text = "正在读取…"
        loadFailed = false
        Thread {
            val cfg = Store.nas()
            var failed = false
            val list = try {
                if (cfg == null || folder.isEmpty()) emptyList() else Repo.listVideos(cfg, folder)
            } catch (e: Exception) {
                failed = true
                Log.w("Episodes", "读取剧集失败: $folder", e)
                emptyList()
            }
            runOnUiThread {
                episodes = list
                loadFailed = failed
                b.tvSub.text = when {
                    failed -> "读取超时，按遥控器 OK 重试"
                    list.isEmpty() -> "没找到视频（按 OK 重试）"
                    else -> "共 ${list.size} 个"
                }
                b.rvEpisodes.adapter = EpAdapter()
                b.rvEpisodes.post {
                    val first = b.rvEpisodes.findViewHolderForAdapterPosition(0)
                    first?.itemView?.requestFocus()
                }
            }
        }.start()
    }

    private inner class EpAdapter : RecyclerView.Adapter<EpAdapter.VH>() {

        /** 记住哪一行有焦点：onResume 重绘后要把高亮补回来，否则会「焦点还在但看不出在哪」 */
        private var focusedPos = -1

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val index: TextView = v.findViewById(R.id.tvIndex)
            val name: TextView = v.findViewById(R.id.tvName)
            val mark: TextView = v.findViewById(R.id.tvMark)
            val bar: View = v.findViewById(R.id.focusBar)
            val row: View = v
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(this@EpisodesActivity).inflate(R.layout.item_episode, parent, false))
        }

        override fun getItemCount(): Int = episodes.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = episodes[position]
            holder.index.text = (position + 1).toString()
            holder.name.text = ep.title
            holder.mark.text = if (Store.isDoneToday(ep.key())) "✓ 看过" else ""

            // 复用归位
            holder.row.animate().cancel()
            holder.row.scaleX = 1f
            holder.row.scaleY = 1f
            holder.row.elevation = 0f
            holder.bar.visibility = View.GONE
            holder.index.setTextColor(getColor(R.color.text_dim))
            holder.name.setTextColor(getColor(R.color.text_primary))
            holder.name.setTypeface(null, Typeface.NORMAL)

            holder.row.setOnClickListener {
                startActivity(
                    Intent(this@EpisodesActivity, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_FILE, ep.file)
                        .putExtra(PlayerActivity.EXTRA_TITLE, ep.title)
                        .putExtra(PlayerActivity.EXTRA_KEY, ep.key())
                        .putExtra(PlayerActivity.EXTRA_SEEK_LOCK, seekLock)
                )
            }

            holder.row.setOnFocusChangeListener { _, has ->
                focusedPos = if (has) holder.bindingAdapterPosition else -1
                applyFocus(holder, has)
            }

            holder.row.setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN &&
                    position == itemCount - 1
                ) true else false
            }

            if (position == focusedPos && holder.row.isFocused) applyFocus(holder, true)
        }

        private fun applyFocus(holder: VH, has: Boolean) {
            holder.bar.visibility = if (has) View.VISIBLE else View.GONE
            holder.row.animate()
                .scaleX(if (has) 1.02f else 1f)
                .scaleY(if (has) 1.02f else 1f)
                .setDuration(if (has) 150 else 120)
                .start()
            holder.row.elevation = if (has) 14f * resources.displayMetrics.density else 0f
            holder.index.setTextColor(getColor(if (has) R.color.accent else R.color.text_dim))
            holder.name.setTypeface(null, if (has) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    override fun onResume() {
        super.onResume()
        b.rvEpisodes.adapter?.notifyDataSetChanged()
    }
}
