package com.example.lifecyclebot

// ... other imports

class MainActivity : AppCompatActivity() {
    // ... other code

    fun clearApiKeys() {
        // Other code...
        telegramBotToken = ""  // Updated this line
        // Other code...
    }

    fun updateDecisionLog() {
        // Other code...
        scrollLog?.post { scrollLog.smoothScrollTo(0, 0) }  // Updated this line
        // Other code...
    }
}