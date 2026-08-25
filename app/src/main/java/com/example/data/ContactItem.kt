package com.example.data

data class ContactItem(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val photoUri: String? = null,
    var isFavorite: Boolean = false,
    val t9Digits: String = ""
)
