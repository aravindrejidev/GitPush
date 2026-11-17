package com.gitpush.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gitpush.app.LocalContainer
import com.gitpush.app.data.AuthState
import com.gitpush.app.ui.home.HomeScreen
import com.gitpush.app.ui.login.LoginScreen
import com.gitpush.app.ui.push.PushScreen
import com.gitpush.app.ui.push.PushViewModel
import com.gitpush.app.ui.repo.CreateRepoScreen
import com.gitpush.app.ui.repo.RepoDetailScreen

@Composable
fun AppRoot() {
    val c = LocalContainer.current
    val auth by c.auth.state.collectAsStateWithLifecycle()
    when (auth) {
        AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        AuthState.SignedOut -> LoginScreen()
        is AuthState.SignedIn -> MainNav()
    }
}

@Composable
private fun MainNav() {
    val c = LocalContainer.current
    val nav = rememberNavController()
    val shared by c.sharedZip.collectAsStateWithLifecycle()

    LaunchedEffect(shared) {
        if (shared != null) nav.navigate("push") { launchSingleTop = true }
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNewPush = { nav.navigate("push") },
                onOpenProject = { id -> nav.navigate("push?projectId=$id") },
                onOpenRepo = { owner, name -> nav.navigate("repo/$owner/$name") },
                onCreateRepo = { nav.navigate("create_repo") },
            )
        }
        composable(
            route = "push?projectId={projectId}&owner={owner}&repo={repo}",
            arguments = listOf(
                navArgument("projectId") { type = NavType.LongType; defaultValue = 0L },
                navArgument("owner") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("repo") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val projectId = entry.arguments?.getLong("projectId") ?: 0L
            val owner = entry.arguments?.getString("owner")
            val repo = entry.arguments?.getString("repo")
            val vm: PushViewModel = viewModel(
                factory = viewModelFactory { initializer { PushViewModel(c, projectId, owner, repo) } },
            )
            PushScreen(vm = vm, onBack = { nav.popBackStack() }, onCreateRepo = { nav.navigate("create_repo") })
        }
        composable("repo/{owner}/{name}") { entry ->
            RepoDetailScreen(
                owner = entry.arguments?.getString("owner").orEmpty(),
                name = entry.arguments?.getString("name").orEmpty(),
                onBack = { nav.popBackStack() },
                onPush = { o, n -> nav.navigate("push?owner=$o&repo=$n") },
            )
        }
        composable("create_repo") {
            CreateRepoScreen(
                onBack = { nav.popBackStack() },
                onCreated = { o, n ->
                    nav.popBackStack()
                    nav.navigate("push?owner=$o&repo=$n")
                },
            )
        }
    }
}
