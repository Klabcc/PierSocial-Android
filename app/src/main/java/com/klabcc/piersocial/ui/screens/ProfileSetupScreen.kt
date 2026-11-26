package com.klabcc.piersocial.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.klabcc.piersocial.data.storage.ImagePipeline
import com.klabcc.piersocial.ui.theme.BorderSubtle
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.MeshViolet
import com.klabcc.piersocial.ui.theme.ObsidianBg
import com.klabcc.piersocial.ui.theme.SurfaceCard
import com.klabcc.piersocial.ui.theme.TextSecondary

@Composable
fun ProfileSetupScreen(
    initialBio: String = "",
    onCompleteSetup: (displayName: String, bio: String, avatar: Bitmap?) -> Unit
) {
    // Profile setup is mandatory on first launch.
    BackHandler(enabled = true) {}

    val context = LocalContext.current
    var displayName by remember { mutableStateOf("") }
    var bio by remember(initialBio) { mutableStateOf(initialBio) }
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            avatarBitmap = ImagePipeline.decodeUriToBitmap(context, uri, 512)
        }
    }

    val cleanName = displayName.trim()
    val isValid = cleanName.length >= 2 && !isSaving

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Set Up Your Profile",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Choose a name and photo so people nearby can recognize you on PierSocial.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(Modifier.height(6.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(MeshCyan.copy(alpha = 0.25f), MeshViolet.copy(alpha = 0.3f)))
                        )
                        .border(2.dp, MeshCyan.copy(alpha = 0.7f), CircleShape)
                        .clickable {
                            avatarPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap!!.asImageBitmap(),
                            contentDescription = "Profile photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(46.dp)
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        avatarPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Default.AddAPhoto, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (avatarBitmap != null) "Change Photo" else "Add Profile Photo")
                }
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = { if (it.length <= 36) displayName = it },
                label = { Text("Display Name *") },
                placeholder = { Text("e.g. Alex Rivera") },
                singleLine = true,
                supportingText = {
                    Text(
                        text = if (cleanName.length < 2) "Required • At least 2 characters" else "Looks great",
                        color = if (cleanName.length < 2) TextSecondary else MeshCyan
                    )
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = bio,
                onValueChange = { if (it.length <= 160) bio = it },
                label = { Text("Bio (optional)") },
                placeholder = { Text("Tell people a little about yourself...") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (isValid) {
                        isSaving = true
                        onCompleteSetup(cleanName, bio.trim(), avatarBitmap)
                    }
                },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MeshCyan,
                    contentColor = Color.Black,
                    disabledContainerColor = SurfaceCard,
                    disabledContentColor = TextSecondary
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "Continue to PierSocial",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
