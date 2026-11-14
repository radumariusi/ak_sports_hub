package ro.ak.sportshub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import ro.ak.sportshub.AkApp
import ro.ak.sportshub.AppContainer

/** Dependențele aplicației, din orice ecran. */
@Composable
@ReadOnlyComposable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as AkApp).container
