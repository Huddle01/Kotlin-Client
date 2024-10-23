package com.huddle01.kotlin_client.live_data

import androidx.lifecycle.MutableLiveData

class SupplierMutableLiveData<T>(supplier: () -> T) : MutableLiveData<T>() {
    fun postValue(invoker: (T) -> Unit) {
        value?.also {
            invoker(it)
            postValue(it)
        }

    }

    init {
        value = supplier()
    }
}
