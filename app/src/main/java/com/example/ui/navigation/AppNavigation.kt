package com.example.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.FamilyWellbeingApplication
import com.example.child.ui.ChildConsentScreen
import com.example.child.ui.ChildDashboardScreen
import com.example.child.ui.ChildModeViewModel
import com.example.child.ui.ChildPermissionsScreen
import com.example.domain.model.UserRole
import com.example.parent.dashboard.ParentDashboardViewModel
import com.example.parent.device.DeviceDetailViewModel
import com.example.parent.ui.ParentAuthScreen
import com.example.parent.ui.ParentDashboardScreen
import com.example.parent.ui.ParentDeviceDetailScreen
import com.example.ui.role.RoleSelectionScreen

object Routes {
    const val ROLE_SELECTION = "role_selection"
    const val CHILD_CONSENT = "child_consent"
    const val CHILD_PERMISSIONS = "child_permissions"
    const val CHILD_DASHBOARD = "child_dashboard"
    const val LIVE_CAMERA = "live_camera"
    const val CAMERA_VIEWFINDER = "camera_viewfinder"
    const val PARENT_AUTH = "parent_auth"
    const val PARENT_DASHBOARD = "parent_dashboard"
    const val PARENT_DEVICE_DETAIL = "parent_device_detail/{deviceId}/{parentUid}"

    fun parentDeviceDetail(deviceId: String, parentUid: String) =
        "parent_device_detail/$deviceId/$parentUid"
}

@Composable
fun AppNavigation(
    context: Context,
    navController: NavHostController = rememberNavController()
) {
    val appContainer = (context.applicationContext as FamilyWellbeingApplication).container
    val localPrefs = appContainer.localPrefs

    val savedRole = localPrefs.getUserRole()
    val isConsentGiven = localPrefs.isConsentGiven()
    val initialRoute = when (savedRole) {
        UserRole.CHILD -> if (isConsentGiven) Routes.CHILD_DASHBOARD else Routes.CHILD_CONSENT
        UserRole.PARENT -> {
            if (appContainer.authRepository.getCurrentUser() != null) {
                Routes.PARENT_DASHBOARD
            } else {
                Routes.PARENT_AUTH
            }
        }
        null -> Routes.ROLE_SELECTION
    }

    NavHost(
        navController = navController,
        startDestination = initialRoute
    ) {
        composable(Routes.ROLE_SELECTION) {
            RoleSelectionScreen(
                onSelectChildMode = {
                    localPrefs.setUserRole(UserRole.CHILD)
                    if (localPrefs.isConsentGiven()) {
                        navController.navigate(Routes.CHILD_DASHBOARD) {
                            popUpTo(Routes.ROLE_SELECTION) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Routes.CHILD_CONSENT)
                    }
                },
                onSelectParentMode = {
                    localPrefs.setUserRole(UserRole.PARENT)
                    val destination = if (appContainer.authRepository.getCurrentUser() != null) {
                        Routes.PARENT_DASHBOARD
                    } else {
                        Routes.PARENT_AUTH
                    }
                    navController.navigate(destination) {
                        popUpTo(Routes.ROLE_SELECTION) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.CHILD_CONSENT) {
            ChildConsentScreen(
                onConsentAccepted = {
                    localPrefs.setConsentGiven(true)
                    navController.navigate(Routes.CHILD_PERMISSIONS) {
                        popUpTo(Routes.CHILD_CONSENT) { inclusive = true }
                    }
                },
                onBack = {
                    navController.navigate(Routes.ROLE_SELECTION) {
                        popUpTo(Routes.CHILD_CONSENT) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.CHILD_PERMISSIONS) {
            ChildPermissionsScreen(
                onContinue = {
                    navController.navigate(Routes.CHILD_DASHBOARD) {
                        popUpTo(Routes.CHILD_PERMISSIONS) { inclusive = true }
                    }
                },
                onBack = {
                    navController.navigate(Routes.CHILD_CONSENT)
                }
            )
        }

        composable(Routes.CHILD_DASHBOARD) {
            val childVm: ChildModeViewModel = viewModel(
                factory = ChildModeViewModel.Factory(appContainer, context.applicationContext)
            )
            ChildDashboardScreen(
                viewModel = childVm,
                onNavigateToPermissions = {
                    navController.navigate(Routes.CHILD_PERMISSIONS)
                },
                onNavigateToLiveCamera = {
                    navController.navigate(Routes.LIVE_CAMERA)
                },
                onSwitchRole = {
                    localPrefs.clearUserRole()
                    navController.navigate(Routes.ROLE_SELECTION) {
                        popUpTo(Routes.CHILD_DASHBOARD) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PARENT_AUTH) {
            val parentVm: ParentDashboardViewModel = viewModel(
                factory = ParentDashboardViewModel.Factory(appContainer)
            )
            ParentAuthScreen(
                viewModel = parentVm,
                onAuthSuccess = {
                    navController.navigate(Routes.PARENT_DASHBOARD) {
                        popUpTo(Routes.PARENT_AUTH) { inclusive = true }
                    }
                },
                onBackToRoleSelection = {
                    localPrefs.clearUserRole()
                    navController.navigate(Routes.ROLE_SELECTION) {
                        popUpTo(Routes.PARENT_AUTH) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PARENT_DASHBOARD) {
            val parentVm: ParentDashboardViewModel = viewModel(
                factory = ParentDashboardViewModel.Factory(appContainer)
            )
            ParentDashboardScreen(
                viewModel = parentVm,
                onSelectDevice = { deviceId, parentUid ->
                    navController.navigate(Routes.parentDeviceDetail(deviceId, parentUid))
                },
                onLogout = {
                    navController.navigate(Routes.PARENT_AUTH) {
                        popUpTo(Routes.PARENT_DASHBOARD) { inclusive = true }
                    }
                },
                onSwitchRole = {
                    localPrefs.clearUserRole()
                    navController.navigate(Routes.ROLE_SELECTION) {
                        popUpTo(Routes.PARENT_DASHBOARD) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.PARENT_DEVICE_DETAIL,
            arguments = listOf(
                navArgument("deviceId") { type = NavType.StringType },
                navArgument("parentUid") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
            val parentUid = backStackEntry.arguments?.getString("parentUid") ?: ""

            val detailVm: DeviceDetailViewModel = viewModel(
                factory = DeviceDetailViewModel.Factory(appContainer, deviceId, parentUid)
            )

            ParentDeviceDetailScreen(
                viewModel = detailVm,
                onBack = { navController.popBackStack() },
                onNavigateToLiveCamera = { navController.navigate(Routes.LIVE_CAMERA) }
            )
        }

        composable(Routes.LIVE_CAMERA) {
            com.example.camera.CameraViewfinderScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.CAMERA_VIEWFINDER) {
            com.example.camera.CameraViewfinderScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
