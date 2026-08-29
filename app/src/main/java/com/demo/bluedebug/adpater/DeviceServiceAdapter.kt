package com.demo.bluedebug.adpater

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.demo.bluedebug.R
import com.demo.bluedebug.data.BleInfoItem

class DeviceServiceAdapter(
    val onItemClick:(BleInfoItem) -> Unit,
    val onExpandClick:(String) -> Unit
) : ListAdapter<BleInfoItem, RecyclerView.ViewHolder>(DiffCallback()) {


    companion object{
        val TYPE_PARENT = 0
        val TYPE_CHILD = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when(getItem(position)){
            is BleInfoItem.ServiceUiModel -> TYPE_PARENT
            is BleInfoItem.CharacteristicUiModel -> TYPE_CHILD
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {

        val inflater = LayoutInflater.from(parent.context)
        return when(viewType){
            TYPE_PARENT ->{
                val rootView = inflater.inflate(R.layout.layout_service_item,parent,false)
                ServiceViewHolder(rootView)
            }
            TYPE_CHILD ->{
                val rootView = inflater.inflate(R.layout.layout_characteristic_item,parent,false)
                CharacteristicViewHolder(rootView)
            }
            else -> throw IllegalArgumentException("UnKown viewType = $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when(item){
            is BleInfoItem.ServiceUiModel -> (holder as ServiceViewHolder).bind(item)
            is BleInfoItem.CharacteristicUiModel -> (holder as CharacteristicViewHolder).bind(item)
        }

//        holder.itemView.setOnClickListener { onItemClick(item) }
    }


    inner class ServiceViewHolder(rootView: View): RecyclerView.ViewHolder(rootView){
        private val serviceName: TextView = rootView.findViewById<TextView>(R.id.tv_service_name)
        private val rightIv: ImageView = rootView.findViewById<ImageView>(R.id.ivArrow)

        fun bind(item: BleInfoItem.ServiceUiModel){
            serviceName.text = item.displayName

            // 展开/收起箭头动画
            val rotation = if (item.isExpanded) 90f else 0f
            rightIv.animate().rotation(rotation).setDuration(200).start()

            rightIv.setOnClickListener { onExpandClick(item.uuid) }
        }
    }

    inner class CharacteristicViewHolder(rootView: View): RecyclerView.ViewHolder(rootView){
        private val tvContent = rootView.findViewById<TextView>(R.id.tvContent)

        fun bind(item: BleInfoItem.CharacteristicUiModel){
            tvContent.text = item.displayName
        }
    }

    // ========== DiffUtil：高效计算差异，避免全量刷新 ==========

    class DiffCallback : DiffUtil.ItemCallback<BleInfoItem>() {

        override fun areItemsTheSame(oldItem: BleInfoItem, newItem: BleInfoItem): Boolean {
            return when {
                oldItem is BleInfoItem.ServiceUiModel && newItem is BleInfoItem.ServiceUiModel ->
                    oldItem.uuid == newItem.uuid

                oldItem is BleInfoItem.CharacteristicUiModel && newItem is BleInfoItem.CharacteristicUiModel ->
                    oldItem.uuid == newItem.uuid

                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: BleInfoItem, newItem: BleInfoItem): Boolean {
            return oldItem == newItem

        }

    }
}