package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.klanary.jigeumchulbal.App
import io.github.klanary.jigeumchulbal.di.AppContainer

/**
 * [App]의 [AppContainer]에서 의존 객체를 꺼내 ViewModel을 만든다.
 * [owner]에 중첩 그래프의 back stack entry를 주면 그 그래프의 화면들이 같은 ViewModel을 공유한다.
 * [key]가 다르면 같은 종류라도 다른 ViewModel이다(역마다 다른 역 시트).
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as App
    return viewModel(viewModelStoreOwner = owner, key = key, factory = viewModelFactory { initializer { create(app.container) } })
}
