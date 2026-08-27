package app.cloudsaver.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.cloudsaver.R
import app.cloudsaver.core.logic.SecureBackup

/**
 * Asks for the password that protects a settings backup.
 * In [confirmMode] (export) the password is typed twice and rated; otherwise
 * (restore) a single field is shown.
 */
@Composable
fun PasswordDialog(
    title: String,
    body: String,
    confirmMode: Boolean,
    errorText: String? = null,
    allowSkip: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }

    val strength = SecureBackup.strengthOf(password)
    val tooShort = password.length < SecureBackup.MIN_PASSWORD_LENGTH
    val mismatch = confirmMode && repeat.isNotEmpty() && repeat != password
    val canConfirm = !tooShort && (!confirmMode || repeat == password)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
                // Scrollable, because a dialog's text slot is not. On a
                // 320x568 screen at font scale 2.0 the bottom of this content
                // sits past the edge with no way to reach it, and the buttons
                // are pushed off with it.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    isError = errorText != null,
                    label = { Text(stringResource(R.string.backup_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
                if (confirmMode) {
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        singleLine = true,
                        isError = mismatch,
                        label = { Text(stringResource(R.string.backup_password_confirm)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                    val hint = when {
                        password.isEmpty() -> null
                        tooShort -> stringResource(R.string.backup_password_short)
                        mismatch -> stringResource(R.string.backup_password_mismatch)
                        strength == SecureBackup.Strength.WEAK ->
                            stringResource(R.string.backup_strength_weak)
                        strength == SecureBackup.Strength.FAIR ->
                            stringResource(R.string.backup_strength_fair)
                        else -> stringResource(R.string.backup_strength_strong)
                    }
                    hint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (tooShort || mismatch) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Text(
                        stringResource(R.string.backup_password_lost_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                errorText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = { onConfirm(password) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            if (allowSkip) {
                TextButton(onClick = { onConfirm("") }) {
                    Text(stringResource(R.string.backup_password_skip))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
