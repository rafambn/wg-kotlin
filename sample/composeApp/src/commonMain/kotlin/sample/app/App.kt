package sample.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun App() {
    Column(modifier = Modifier.padding(24.dp)) {
        BasicText("kmp-vpn")
        BasicText("Rust exports: JNI (BoringTun style)")
    }

}
