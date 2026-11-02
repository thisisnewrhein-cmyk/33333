package com.routescout.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.routescout.mobile.ui.RouteScoutApp
import com.routescout.mobile.ui.theme.RouteScoutTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 10, 18)
        window.navigationBarColor = android.graphics.Color.rgb(7, 10, 18)

        setContent {
            RouteScoutTheme {
                RouteScoutApp()
            }
        }
    }
}
