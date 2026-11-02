package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.klanary.jigeumchulbal.App
import io.github.klanary.jigeumchulbal.di.AppContainer

/** [App]의 [AppContainer]에서 의존 객체를 꺼내 ViewModel을 만든다. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as App
    return viewModel(factory = viewModelFactory { initializer { create(app.container) } })
}
