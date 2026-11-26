package com.klabcc.piersocial

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.klabcc.piersocial.crypto.CryptoManager
import com.klabcc.piersocial.mesh.MeshController
import com.klabcc.piersocial.notifications.DmNotificationHelper
import com.klabcc.piersocial.ui.components.MeshInspectorSheet
import com.klabcc.piersocial.ui.components.MeshStatusPill
import com.klabcc.piersocial.ui.screens.CreateScreen
import com.klabcc.piersocial.ui.screens.DmChatScreen
import com.klabcc.piersocial.ui.screens.DmListScreen
import com.klabcc.piersocial.ui.screens.ExploreScreen
import com.klabcc.piersocial.ui.screens.FeedScreen
import com.klabcc.piersocial.ui.screens.PermissionsScreen
import com.klabcc.piersocial.ui.screens.ProfileScreen
import com.klabcc.piersocial.ui.screens.ProfileSetupScreen
import com.klabcc.piersocial.ui.screens.SettingsScreen
import com.klabcc.piersocial.ui.screens.StorageSettingsScreen
import com.klabcc.piersocial.ui.screens.StoryViewerScreen
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.ObsidianBg
import com.klabcc.piersocial.ui.theme.PierSocialTheme
import com.klabcc.piersocial.ui.theme.SurfaceDark
import com.klabcc.piersocial.ui.theme.TextSecondary
import com.klabcc.piersocial.ui.viewmodel.PierViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    private val pendingNotificationPeerId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            PierSocialTheme {
                PierSocialRoot(
                    pendingNotificationPeerIdFlow = pendingNotificationPeerId,
                    onConsumeNotificationPeerId = { pendingNotificationPeerId.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val peerId = intent?.getStringExtra(DmNotificationHelper.EXTRA_DM_PEER_ID)
        if (!peerId.isNullOrBlank()) {
            pendingNotificationPeerId.value = peerId
        }
    }
}

private sealed class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    data object Feed : BottomTab("tab_feed", "Feed", Icons.Default.Home)
    data object Create : BottomTab("tab_create", "Create", Icons.Default.AddCircleOutline)
    data object Dms : BottomTab("tab_dms", "DMs", Icons.AutoMirrored.Filled.Chat)
}

private const val ROUTE_PERMISSIONS = "onboarding_permissions"
private const val ROUTE_PROFILE_SETUP = "onboarding_profile_setup"
private const val ROUTE_EXPLORE = "top_explore"
private const val ROUTE_PROFILE = "top_profile"
private const val ROUTE_SETTINGS = "top_settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PierSocialRoot(
    viewModel: PierViewModel = viewModel(),
    pendingNotificationPeerIdFlow: StateFlow<String?> = MutableStateFlow(null),
    onConsumeNotificationPeerId: () -> Unit = {}
) {
    val context = LocalContext.current
    val profileSetupCompleted by viewModel.profileSetupCompleted.collectAsState()
    val setupDone = profileSetupCompleted
    if (setupDone == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianBg),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MeshCyan)
        }
        return
    }

    val navController = rememberNavController()
    val hasPermsInitially = remember { MeshController.hasRequiredMeshPermissions(context) }
    val initialRoute = remember(setupDone, hasPermsInitially) {
        when {
            !hasPermsInitially -> ROUTE_PERMISSIONS
            !setupDone -> ROUTE_PROFILE_SETUP
            else -> BottomTab.Feed.route
        }
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.setAppInForeground(true)
        viewModel.refreshPermissions()
        if (MeshController.hasRequiredMeshPermissions(context)) {
            viewModel.startMeshService()
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
        viewModel.setAppInForeground(false)
    }

    val developerMode by viewModel.developerMode.collectAsState()
    val dmNotificationsEnabled by viewModel.dmNotificationsEnabled.collectAsState()
    val pendingNotificationPeerId by pendingNotificationPeerIdFlow.collectAsState()
    val localProfile by viewModel.localProfile.collectAsState()
    val allProfiles by viewModel.allProfiles.collectAsState()
    val profilesByPeerId = remember(allProfiles) { allProfiles.associateBy { it.peerId } }
    val feedPosts by viewModel.feedPosts.collectAsState()
    val savedPosts by viewModel.savedPosts.collectAsState()
    val activeNotes by viewModel.activeNotes.collectAsState()
    val reactionsByPostId by viewModel.postReactionsByPostId.collectAsState()
    val postLikesByPostId by viewModel.postLikesByPostId.collectAsState()
    val storyLikesByStoryId by viewModel.storyLikesByStoryId.collectAsState()
    val votesByPostId by viewModel.pollVotesByPostId.collectAsState()
    val storyGroups by viewModel.storyGroups.collectAsState()
    val conversations by viewModel.conversationSummaries.collectAsState()
    val storageStats by viewModel.storageStats.collectAsState()
    val pinnedPosts by viewModel.pinnedPosts.collectAsState()
    val activeTransfers by viewModel.activeTransfers.collectAsState()
    val radioState by viewModel.radioState.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val activeConnections by viewModel.activeConnections.collectAsState()
    val meshLogs by viewModel.meshLogs.collectAsState()

    var showMeshInspector by remember { mutableStateOf(false) }
    var createStoryInitialMode by remember { mutableStateOf(false) }
    var pendingDmReplyContext by remember { mutableStateOf<Pair<String, String>?>(null) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(pendingNotificationPeerId, setupDone, currentRoute) {
        val targetPeerId = pendingNotificationPeerId
        if (!targetPeerId.isNullOrBlank() &&
            setupDone &&
            currentRoute != null &&
            currentRoute != ROUTE_PERMISSIONS &&
            currentRoute != ROUTE_PROFILE_SETUP
        ) {
            onConsumeNotificationPeerId()
            navController.navigate("dm_chat/$targetPeerId") {
                launchSingleTop = true
            }
        }
    }

    val bottomTabs = listOf(
        BottomTab.Feed,
        BottomTab.Create,
        BottomTab.Dms
    )
    val topRoutes = setOf(ROUTE_EXPLORE, ROUTE_PROFILE, ROUTE_SETTINGS)
    val showBottomBar = bottomTabs.any { it.route == currentRoute }
    val showTopBar = showBottomBar || currentRoute in topRoutes

    Scaffold(
        containerColor = ObsidianBg,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (showTopBar) {
                val isSubTopDestination = currentRoute in topRoutes
                val topTitle = when (currentRoute) {
                    ROUTE_EXPLORE -> "Explore"
                    ROUTE_PROFILE -> "Profile"
                    ROUTE_SETTINGS -> "Settings"
                    else -> "PierSocial"
                }
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark),
                    navigationIcon = {
                        if (isSubTopDestination) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White
                                )
                            }
                        }
                    },
                    title = {
                        Text(
                            text = topTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    },
                    actions = {
                        if (developerMode) {
                            MeshStatusPill(
                                radioState = radioState,
                                peerCount = discoveredPeers.size,
                                connectionCount = activeConnections.size,
                                onClick = { showMeshInspector = true }
                            )
                        }
                        IconButton(
                            onClick = {
                                if (currentRoute != ROUTE_EXPLORE) {
                                    navController.navigate(ROUTE_EXPLORE) {
                                        launchSingleTop = true
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = "Explore",
                                tint = if (currentRoute == ROUTE_EXPLORE) MeshCyan else Color.White
                            )
                        }
                        IconButton(
                            onClick = {
                                if (currentRoute != ROUTE_PROFILE) {
                                    navController.navigate(ROUTE_PROFILE) {
                                        launchSingleTop = true
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile",
                                tint = if (currentRoute == ROUTE_PROFILE) MeshCyan else Color.White
                            )
                        }
                        IconButton(
                            onClick = {
                                if (currentRoute != ROUTE_SETTINGS) {
                                    navController.navigate(ROUTE_SETTINGS) {
                                        launchSingleTop = true
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = if (currentRoute == ROUTE_SETTINGS) MeshCyan else Color.White
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = SurfaceDark) {
                    bottomTabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (tab == BottomTab.Create) createStoryInitialMode = false
                                navController.navigate(tab.route) {
                                    popUpTo(BottomTab.Feed.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MeshCyan,
                                selectedTextColor = MeshCyan,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = MeshCyan.copy(alpha = 0.14f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = initialRoute
        ) {
            composable(ROUTE_PERMISSIONS) {
                PermissionsScreen(
                    fingerprint = viewModel.localIdentity.fingerprint,
                    handle = viewModel.localIdentity.handle,
                    developerMode = developerMode,
                    onContinue = { startMesh ->
                        viewModel.refreshPermissions()
                        if (startMesh) viewModel.startMeshService()
                        val nextRoute = if (setupDone) BottomTab.Feed.route else ROUTE_PROFILE_SETUP
                        navController.navigate(nextRoute) {
                            popUpTo(ROUTE_PERMISSIONS) { inclusive = true }
                        }
                    }
                )
            }

            composable(ROUTE_PROFILE_SETUP) {
                ProfileSetupScreen(
                    onCompleteSetup = { name, bio, avatar ->
                        viewModel.completeProfileSetup(name, bio, avatar) {
                            navController.navigate(BottomTab.Feed.route) {
                                popUpTo(ROUTE_PROFILE_SETUP) { inclusive = true }
                            }
                        }
                    }
                )
            }

            composable(BottomTab.Feed.route) {
                FeedScreen(
                    contentPadding = innerPadding,
                    localProfile = localProfile,
                    localPeerId = viewModel.localIdentity.peerId,
                    storyGroups = storyGroups,
                    posts = feedPosts,
                    savedPosts = savedPosts,
                    reactionsByPostId = reactionsByPostId,
                    postLikesByPostId = postLikesByPostId,
                    storyLikesByStoryId = storyLikesByStoryId,
                    votesByPostId = votesByPostId,
                    activeTransfers = activeTransfers,
                    developerMode = developerMode,
                    onOpenStory = { peerId -> navController.navigate("story_viewer/$peerId") },
                    onCreateStory = {
                        createStoryInitialMode = true
                        navController.navigate(BottomTab.Create.route)
                    },
                    onCreatePost = {
                        createStoryInitialMode = false
                        navController.navigate(BottomTab.Create.route)
                    },
                    onOpenProfile = { peerId ->
                        if (peerId == viewModel.localIdentity.peerId) {
                            navController.navigate(ROUTE_PROFILE) { launchSingleTop = true }
                        } else {
                            navController.navigate("peer_profile/$peerId")
                        }
                    },
                    onOpenDm = { peerId -> navController.navigate("dm_chat/$peerId") },
                    onToggleLike = viewModel::toggleLike,
                    onDoubleTapLike = viewModel::ensureLiked,
                    onToggleSave = viewModel::toggleSavePost,
                    onTogglePin = viewModel::togglePinPost,
                    onReactPost = viewModel::reactToPost,
                    onVotePoll = viewModel::voteOnPoll
                )
            }

            composable(ROUTE_EXPLORE) {
                ExploreScreen(
                    contentPadding = innerPadding,
                    profiles = allProfiles,
                    posts = feedPosts,
                    discoveredBlePeers = discoveredPeers,
                    activeTransfers = activeTransfers,
                    developerMode = developerMode,
                    onOpenProfile = { peerId -> navController.navigate("peer_profile/$peerId") },
                    onOpenDm = { peerId -> navController.navigate("dm_chat/$peerId") },
                    onToggleFollow = viewModel::toggleFollow,
                    onToggleMute = viewModel::toggleMute,
                    onTogglePinProfile = viewModel::togglePinProfile
                )
            }

            composable(BottomTab.Create.route) {
                CreateScreen(
                    contentPadding = innerPadding,
                    initialStoryMode = createStoryInitialMode,
                    developerMode = developerMode,
                    onPublishPost = { bmp, adj, caption, audioFile, audioDurationMs, pollQuestion, pollOptions ->
                        viewModel.publishFeedPost(
                            bitmap = bmp,
                            adjustments = adj,
                            caption = caption,
                            audioFile = audioFile,
                            audioDurationMs = audioDurationMs,
                            pollQuestion = pollQuestion,
                            pollOptions = pollOptions
                        ) {
                            navController.navigate(BottomTab.Feed.route) {
                                popUpTo(BottomTab.Feed.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    },
                    onPublishStory = { bmp, adj, overlay, caption, audioFile, audioDurationMs ->
                        viewModel.publishStory(bmp, adj, overlay, caption, audioFile, audioDurationMs) {
                            navController.navigate(BottomTab.Feed.route) {
                                popUpTo(BottomTab.Feed.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    }
                )
            }

            composable(BottomTab.Dms.route) {
                DmListScreen(
                    contentPadding = innerPadding,
                    localProfile = localProfile,
                    notes = activeNotes,
                    conversations = conversations,
                    developerMode = developerMode,
                    onPublishNote = viewModel::publishNote,
                    onClearNote = viewModel::clearLocalNote,
                    onReplyToNote = { peerId, noteText ->
                        pendingDmReplyContext = peerId to "Note: $noteText"
                        navController.navigate("dm_chat/$peerId")
                    },
                    onOpenChat = { peerId -> navController.navigate("dm_chat/$peerId") }
                )
            }

            composable(ROUTE_PROFILE) {
                val myPosts by viewModel.observePostsByAuthor(viewModel.localIdentity.peerId)
                    .collectAsState(initial = emptyList())
                ProfileScreen(
                    contentPadding = innerPadding,
                    profile = localProfile,
                    posts = myPosts,
                    allProfiles = allProfiles,
                    storageStats = storageStats,
                    activeTransfers = activeTransfers,
                    isOwnProfile = true,
                    developerMode = developerMode,
                    onUpdateProfile = { name, bio, bmp -> viewModel.updateProfile(name, bio, bmp) },
                    onOpenStorageSettings = { navController.navigate("storage_settings") },
                    onToggleFollow = viewModel::toggleFollow,
                    onToggleMute = viewModel::toggleMute,
                    onTogglePinProfile = viewModel::togglePinProfile,
                    onTogglePinPost = viewModel::togglePinPost,
                    onOpenDm = { peerId -> navController.navigate("dm_chat/$peerId") }
                )
            }

            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    contentPadding = innerPadding,
                    localProfile = localProfile,
                    storageStats = storageStats,
                    radioState = radioState,
                    discoveredPeerCount = discoveredPeers.size,
                    activeConnectionCount = activeConnections.size,
                    developerMode = developerMode,
                    dmNotificationsEnabled = dmNotificationsEnabled,
                    onToggleDeveloperMode = viewModel::setDeveloperMode,
                    onToggleDmNotifications = viewModel::setDmNotificationsEnabled,
                    onToggleMeshService = {
                        if (radioState.isServiceRunning) viewModel.stopMeshService()
                        else viewModel.startMeshService()
                    },
                    onOpenStorageSettings = { navController.navigate("storage_settings") },
                    onOpenMeshInspector = { showMeshInspector = true }
                )
            }

            composable(
                route = "peer_profile/{peerId}",
                arguments = listOf(navArgument("peerId") { type = NavType.StringType })
            ) { entry ->
                val peerId = entry.arguments?.getString("peerId") ?: return@composable
                val peerProfile by viewModel.observeProfile(peerId).collectAsState(initial = null)
                val peerPosts by viewModel.observePostsByAuthor(peerId).collectAsState(initial = emptyList())
                ProfileScreen(
                    contentPadding = innerPadding,
                    profile = peerProfile,
                    posts = peerPosts,
                    allProfiles = allProfiles,
                    storageStats = storageStats,
                    activeTransfers = activeTransfers,
                    isOwnProfile = false,
                    developerMode = developerMode,
                    onBack = { navController.popBackStack() },
                    onUpdateProfile = { _, _, _ -> },
                    onOpenStorageSettings = {},
                    onToggleFollow = viewModel::toggleFollow,
                    onToggleMute = viewModel::toggleMute,
                    onTogglePinProfile = viewModel::togglePinProfile,
                    onTogglePinPost = viewModel::togglePinPost,
                    onOpenDm = { id -> navController.navigate("dm_chat/$id") }
                )
            }

            composable(
                route = "dm_chat/{peerId}",
                arguments = listOf(navArgument("peerId") { type = NavType.StringType })
            ) { entry ->
                val peerId = entry.arguments?.getString("peerId") ?: return@composable
                DisposableEffect(peerId) {
                    viewModel.setActiveChatPeerId(peerId)
                    onDispose {
                        viewModel.setActiveChatPeerId(null)
                    }
                }
                val profileFlow = remember(peerId) { viewModel.observeProfile(peerId) }
                val conversationFlow = remember(peerId) { viewModel.observeConversation(peerId) }
                val peer by profileFlow.collectAsState(initial = null)
                val messages by conversationFlow.collectAsState(initial = emptyList())
                val initialReply = remember(peerId, pendingDmReplyContext) {
                    val ctx = pendingDmReplyContext
                    if (ctx?.first == peerId) {
                        pendingDmReplyContext = null
                        ctx.second
                    } else {
                        null
                    }
                }
                val sessionFp = remember(peer?.x25519PubKey, developerMode) {
                    if (!developerMode) return@remember "E2EE Ready"
                    val xPub = peer?.x25519PubKey ?: return@remember "Pending X25519"
                    runCatching {
                        viewModel.repository.cryptoManager.sharedSessionFingerprint(
                            CryptoManager.decodeBase64(xPub),
                            viewModel.localIdentity
                        )
                    }.getOrDefault("E2EE Ready")
                }
                DmChatScreen(
                    peer = peer,
                    localPeerId = viewModel.localIdentity.peerId,
                    messages = messages,
                    sessionFingerprint = sessionFp,
                    initialReplyPreview = initialReply,
                    developerMode = developerMode,
                    onSendMessage = { text, replyId, replyPreview ->
                        viewModel.sendDirectMessage(peerId, text, replyId, replyPreview)
                    },
                    onSendReaction = { targetMsgId, emoji ->
                        viewModel.sendDmReaction(peerId, targetMsgId, emoji)
                    },
                    onSetDisappearTimer = { durationMs ->
                        viewModel.setDmDisappearTimer(peerId, durationMs)
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "story_viewer/{peerId}",
                arguments = listOf(navArgument("peerId") { type = NavType.StringType })
            ) { entry ->
                val peerId = entry.arguments?.getString("peerId") ?: return@composable
                val group = storyGroups.firstOrNull { it.author.peerId == peerId }
                StoryViewerScreen(
                    storyGroup = group,
                    localPeerId = viewModel.localIdentity.peerId,
                    storyLikesByStoryId = storyLikesByStoryId,
                    profilesByPeerId = profilesByPeerId,
                    activeTransfers = activeTransfers,
                    developerMode = developerMode,
                    onMarkViewed = viewModel::markStoryViewed,
                    onTogglePinStory = viewModel::togglePinStory,
                    onToggleLikeStory = viewModel::toggleStoryLike,
                    onEnsureLikeStory = viewModel::ensureStoryLiked,
                    onReplyToStory = { authorPeerId, replyText ->
                        viewModel.sendDirectMessage(
                            recipientPeerId = authorPeerId,
                            text = replyText,
                            replyToPreview = "Story reply"
                        )
                    },
                    onClose = { navController.popBackStack() }
                )
            }

            composable("storage_settings") {
                StorageSettingsScreen(
                    stats = storageStats,
                    pinnedPosts = pinnedPosts,
                    developerMode = developerMode,
                    onSelectCap = viewModel::setRetentionCap,
                    onToggleAutoEvict = viewModel::setAutoEvict,
                    onRunEvictionNow = viewModel::runEvictionNow,
                    onUnpinPost = viewModel::togglePinPost,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    if (showMeshInspector && developerMode) {
        MeshInspectorSheet(
            radioState = radioState,
            discoveredPeers = discoveredPeers,
            activeConnections = activeConnections,
            activeTransfers = activeTransfers,
            meshLogs = meshLogs,
            onForceSync = viewModel::forceSync,
            onToggleService = {
                if (radioState.isServiceRunning) viewModel.stopMeshService()
                else viewModel.startMeshService()
            },
            onDismiss = { showMeshInspector = false }
        )
    }
}
