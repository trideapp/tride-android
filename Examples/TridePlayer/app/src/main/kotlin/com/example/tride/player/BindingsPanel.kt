/**
 * Every binding and trigger the open scene declares (`availableBindings()`, listed once per scene by the
 * caller), each with a control for its kind: a trigger fires, a bool switches, an enum picks a value, a
 * number takes a typed value. A computed binding is the scene's own — shown, not written. Vectors and
 * colours are written from code.
 *
 * Each control starts from the scene's current value (`getBinding`), read when the sheet opens.
 */
package com.example.tride.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.tride.core.TrideValue
import studio.tride.kit.TrideKitBinding
import studio.tride.kit.TrideKitBindingKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindingsPanel(
    bindings: List<TrideKitBinding>,
    current: (name: String) -> TrideValue?,
    write: (name: String, value: TrideValue?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(bindings, key = { it.name }) { binding ->
                BindingRow(binding, remember(binding.name) { current(binding.name) }) { value -> write(binding.name, value) }
            }
            item { Text(" ") }
        }
    }
}

/** One binding: its name and kind, and the control that writes it (`null` = fire the trigger). */
@Composable
private fun BindingRow(binding: TrideKitBinding, initial: TrideValue?, write: (TrideValue?) -> Unit) {
    var last by remember(binding.name) { mutableStateOf(initial) }
    val send: (TrideValue?) -> Unit = { value -> write(value); if (value != null) last = value }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${binding.name} (${binding.kind.name.lowercase()})", fontSize = 13.sp)
        when {
            binding.computed -> Text("computed by the scene: ${valueText(last)}", color = Color.Gray, fontSize = 12.sp)
            binding.kind == TrideKitBindingKind.TRIGGER -> FilledTonalButton(onClick = { send(null) }) { Text("Fire") }
            binding.kind == TrideKitBindingKind.BOOL -> {
                val on = (last as? TrideValue.Bool)?.value
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = on == false, onClick = { send(TrideValue.Bool(false)) }, label = { Text("false") })
                    FilterChip(selected = on == true, onClick = { send(TrideValue.Bool(true)) }, label = { Text("true") })
                }
            }
            binding.kind == TrideKitBindingKind.ENUM -> {
                val picked = (last as? TrideValue.String)?.value
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in binding.options) {
                        FilterChip(selected = picked == option, onClick = { send(TrideValue.String(option)) }, label = { Text(option) })
                    }
                }
            }
            binding.kind == TrideKitBindingKind.NUMBER -> {
                var text by remember(binding.name) { mutableStateOf((last as? TrideValue.Number)?.value?.toString() ?: "") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text("number") },
                        modifier = Modifier.width(160.dp),
                    )
                    FilledTonalButton(onClick = { text.toDoubleOrNull()?.let { send(TrideValue.Number(it)) } }) { Text("Write") }
                }
            }
            else -> Text("written from code: ${valueText(last)}", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun valueText(v: TrideValue?): String = when (v) {
    is TrideValue.Bool -> v.value.toString()
    is TrideValue.Number -> v.value.toString()
    is TrideValue.String -> v.value
    is TrideValue.Vec3 -> v.value.joinToString(prefix = "[", postfix = "]")
    null -> "—"
}
