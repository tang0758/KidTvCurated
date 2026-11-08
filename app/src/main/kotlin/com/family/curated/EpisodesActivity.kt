package com.family.curated

import android.content.Intent
import android.os.Bundle
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (KioskKeys.systemBlocked(keyCode)) return true
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
        b.tvSub.text = "正在读取…"

        b.rvEpisodes.layoutManager = LinearLayoutManager(this)

        Thread {
            val cfg = Store.nas()
            val list = try {
                if (cfg == null || folder.isEmpty()) emptyList() else Repo.listVideos(cfg, folder)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                episodes = list
                b.tvSub.text = if (list.isEmpty()) "这个文件夹里没有视频" else "共 ${list.size} 个"
                b.rvEpisodes.adapter = EpAdapter()
                b.rvEpisodes.post {
                    val first = b.rvEpisodes.findViewHolderForAdapterPosition(0)
                    first?.itemView?.requestFocus()
                }
            }
        }.start()
    }

    private inner class EpAdapter : RecyclerView.Adapter<EpAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val index: TextView = v.findViewById(R.id.tvIndex)
            val name: TextView = v.findViewById(R.id.tvName)
            val mark: TextView = v.findViewById(R.id.tvMark)
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

            holder.row.setOnClickListener {
                startActivity(
                    Intent(this@EpisodesActivity, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_FILE, ep.file)
                        .putExtra(PlayerActivity.EXTRA_TITLE, ep.title)
                        .putExtra(PlayerActivity.EXTRA_KEY, ep.key())
                        .putExtra(PlayerActivity.EXTRA_SEEK_LOCK, seekLock)
                )
            }

            holder.row.setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.01f else 1f).scaleY(if (has) 1.01f else 1f)
                    .setDuration(120).start()
            }

            holder.row.setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN &&
                    position == itemCount - 1
                ) true else false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        b.rvEpisodes.adapter?.notifyDataSetChanged()
    }
}
