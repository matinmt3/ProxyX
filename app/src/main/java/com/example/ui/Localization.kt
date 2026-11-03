package com.example.ui

import java.util.Locale

fun isPersianLanguage(language: String): Boolean {
    return language == "fa" || (language == "system" && Locale.getDefault().language == "fa")
}
