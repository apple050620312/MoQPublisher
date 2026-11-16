package com.sangege.moqpublisher

enum class AppLanguage(val tag: String?) {
    TRADITIONAL_CHINESE("zh-TW"), ENGLISH("en"), SYSTEM(null);

    companion object {
        fun fromPreference(value: String?): AppLanguage = entries.firstOrNull { it.name == value } ?: TRADITIONAL_CHINESE
    }
}

enum class AppTheme {
    DARK, LIGHT;

    companion object {
        fun fromPreference(value: String?): AppTheme = entries.firstOrNull { it.name == value } ?: DARK
    }
}
