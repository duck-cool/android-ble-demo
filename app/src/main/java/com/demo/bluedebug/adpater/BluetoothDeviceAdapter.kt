package com.demo.bluedebug.adpater

import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.demo.bluedebug.R
import com.demo.bluedebug.data.BluetoothDeviceItem

class BluetoothDeviceAdapter(
    private var devices: MutableList<BluetoothDeviceItem>?,
    private val onItemClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

    val list = devices ?: mutableListOf<BluetoothDeviceItem>()

    fun setItems(data: List<BluetoothDeviceItem>){
        if (!data.isEmpty()){
            list.clear()
            list.addAll(data)
        }
    }

    fun setItem(device: BluetoothDeviceItem){
        if (device != null){
            list.add(device)
            notifyItemInserted(list.size-1)
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.bluetooth_device_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        holder.deviceName.text = item.getDeviceInfo()
        holder.deviceName.setOnClickListener {
            onItemClick(item.getDevice())
        }
    }

    override fun getItemCount(): Int {
        return list.size
    }


    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val deviceName = itemView.findViewById<TextView>(R.id.txtDeviceName)
    }
}