package com.chdroid.app.ui.screen

import com.chdroid.app.ui.component.PrimaryActionBottomBar
import androidx.compose.ui.graphics.Color

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.chdroid.app.util.ValidationUtils
import com.chdroid.app.util.ContainerManager
import com.chdroid.app.ui.util.FocusUtils
import com.chdroid.app.ui.util.rememberClearFocus
import androidx.compose.ui.platform.LocalContext
import com.chdroid.app.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContainerNameScreen(
    initialName: String = "",
    existingContainerNames: List<String> = emptyList(),
    recommendationNotice: String? = null,
    onNext: (String) -> Unit,
    onClose: () -> Unit
) {
    var containerName by remember { mutableStateOf(initialName) }
    var nameError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val nameFocusRequester = remember { FocusRequester() }
    val clearFocus = rememberClearFocus()

    // Request focus on container name field when screen appears
    LaunchedEffect(Unit) {
        if (containerName.isEmpty()) {
            nameFocusRequester.requestFocus()
        }
    }

    // Check for duplicate name when container name changes (instant check using cached names)
    LaunchedEffect(containerName, existingContainerNames) {
        if (containerName.isNotBlank()) {
            // Validate format first (against the normalized name, which is what we store)
            val normalizedName = ValidationUtils.normalizeContainerName(containerName)
            val formatResult = ValidationUtils.validateContainerName(normalizedName, context)
            if (formatResult.isError) {
                nameError = formatResult.errorMessage
            } else {
                // Check for duplicate - instant check using cached names
                // Compare both exact names and sanitized names
                // This handles cases where user enters "My Container" and there's "My-Container" or vice versa
                val sanitizedInput = ContainerManager.sanitizeContainerName(normalizedName)
                val isDuplicate = existingContainerNames.any { existingName ->
                    // Check exact match (case-insensitive)
                    existingName.equals(normalizedName, ignoreCase = true) ||
                    // Check sanitized match (handles spaces vs dashes)
                    ContainerManager.sanitizeContainerName(existingName).equals(sanitizedInput, ignoreCase = true)
                }
                if (isDuplicate) {
                    nameError = context.getString(R.string.container_name_exists)
                } else {
                    nameError = null
                }
            }
        } else {
            nameError = null
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.container_setup), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = context.getString(R.string.close))
                    }
                }
            )
        },
        bottomBar = {
            val isNextValid = containerName.isNotBlank() && nameError == null
            PrimaryActionBottomBar(
                label = context.getString(R.string.next_configuration),
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = {
                    clearFocus()
                    val normalizedName = ValidationUtils.normalizeContainerName(containerName)
                    val nameResult = ValidationUtils.validateContainerName(normalizedName, context)
                    if (!nameResult.isError) {
                        onNext(normalizedName)
                    } else {
                        nameError = nameResult.errorMessage
                    }
                },
                enabled = isNextValid
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
            Text(
                text = context.getString(R.string.container_information),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            recommendationNotice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Container Name
            OutlinedTextField(
                value = containerName,
                onValueChange = {
                    containerName = it
                    // Validation will be handled by LaunchedEffect
                },
                label = { Text(context.getString(R.string.container_name_label)) },
                placeholder = { Text(context.getString(R.string.container_name_placeholder)) },
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocusRequester),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next,
                    keyboardType = KeyboardType.Text
                ),
                keyboardActions = FocusUtils.clearFocusKeyboardActions()
            )

            Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
