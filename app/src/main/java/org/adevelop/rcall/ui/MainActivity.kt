package org.adevelop.rcall.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.adevelop.rcall.R
import org.adevelop.rcall.ui.theme.RCallTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RCallTheme {
                val scope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                Scaffold(modifier = Modifier.fillMaxSize(), snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState)
                }) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding), contentAlignment = Alignment.Center
                    ) {
                        EditContent { roomId ->
                            if (roomId.isNotEmpty()) {
                                val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                                    putExtra("room", roomId)
                                }
                                startActivity(intent)
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(getString(R.string.room_field_error))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditContent(onClick: (String) -> Unit) {
    val text = rememberTextFieldState()

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)) {
        TextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            state = text,
            label = {
                Text(
                    text = stringResource(R.string.room_field)
                )
            }
        )
        Button(onClick = {
            onClick(text.text.toString())
        }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.room_connect)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    RCallTheme {
        EditContent { }
    }
}