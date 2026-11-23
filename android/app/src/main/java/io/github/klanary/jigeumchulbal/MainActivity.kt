package io.github.klanary.jigeumchulbal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import io.github.klanary.jigeumchulbal.map.MapExperiment

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // P3 Task 2에서 내비게이션으로 바뀐다.
        setContent {
            MaterialTheme {
                MapExperiment()
            }
        }
    }
}
