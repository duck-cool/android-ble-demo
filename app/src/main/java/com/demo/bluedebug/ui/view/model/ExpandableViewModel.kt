package com.demo.bluedebug.ui.view.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.demo.bluedebug.data.BleInfoItem

class ExpandableViewModel: ViewModel() {

    private var serviceItems = MutableLiveData<List<BleInfoItem.ServiceUiModel>>()

    private val _flatList = MutableLiveData<List<BleInfoItem>>(emptyList())
    val flatList: LiveData<List<BleInfoItem>> = _flatList

    fun loadDate(data: List<BleInfoItem.ServiceUiModel>){
        serviceItems.value = data
        _flatList.value = data
    }

    fun toggleParent(uuid: String){
        val currentParents = serviceItems.value ?: return
        val currentFlatList = _flatList.value?.toMutableList() ?: return

        val parentIndex = currentParents.indexOfFirst { it.uuid == uuid }
        if (parentIndex == -1) return

        val parent = currentParents[parentIndex]
        val isCurExpanded = parent.isExpanded

        val updateParent = parent.copy(isExpanded = !isCurExpanded)
        val updateCurParents = currentParents.toMutableList().apply {
            this[parentIndex] = updateParent
        }
        serviceItems.value = updateCurParents

        val flatListIndex = currentFlatList.indexOfFirst {
            it is BleInfoItem.ServiceUiModel && it.uuid == uuid
        }
        if (flatListIndex == -1) return

        if (isCurExpanded){
            currentFlatList.removeAll { it is BleInfoItem.CharacteristicUiModel && it.service_uuid == uuid }
            currentFlatList[flatListIndex] = updateParent
        }else{
            currentFlatList[flatListIndex] = updateParent
            val childs = updateParent.characteristics
            currentFlatList.addAll(flatListIndex+1,childs)
        }
        _flatList.value = currentFlatList
    }

}