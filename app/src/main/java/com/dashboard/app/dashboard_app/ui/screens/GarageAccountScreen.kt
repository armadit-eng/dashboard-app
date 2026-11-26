package com.dashboard.app.dashboard_app.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ElectricMoped
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.ConnectionStateX
import com.dashboard.app.dashboard_app.model.MatrixQueueItem
import com.dashboard.app.dashboard_app.service.TileCacheService
import com.dashboard.app.dashboard_app.ui.components.CockpitCard
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import com.dashboard.app.dashboard_app.ui.theme.kAccentPalettes
import com.dashboard.app.dashboard_app.ui.theme.kGarageLiveries
import com.dashboard.app.dashboard_app.viewmodel.DashboardUiState
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GarageAccountScreen(
    state: DashboardUiState,
    onUpdateSettings: (AppSettings) -> Unit,
    onOpenMotovlogCam: () -> Unit,
    onPickAvatarImage: () -> Unit,
    onSetContactRelay: (Boolean) -> String?,
    onShowMessage: (String) -> Unit,
    onSetMatrixPower: (Boolean) -> Unit,
    onToggleMatrixAutoRotate: (Boolean) -> Unit,
    onAddMatrixItem: (String, Int) -> Unit,
    onSendMatrixItemNow: (MatrixQueueItem) -> Unit,
    onDeleteMatrixItem: (String) -> Unit,
    onSendEsp1Command: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalCockpitColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = state.settings
    val esp1 = state.esp1Data
    val esp1Ok = state.esp1ConnState == ConnectionStateX.CONNECTED

    var matrixText by remember { mutableStateOf("") }
    var matrixEffectId by remember { mutableIntStateOf(0) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var pendingRelayTarget by remember { mutableStateOf<Boolean?>(null) }

    val activeLivery = kGarageLiveries.firstOrNull { it.id == s.garageStyleId } ?: kGarageLiveries.first()

    pendingRelayTarget?.let { targetOn ->
        AlertDialog(
            onDismissRequest = { pendingRelayTarget = null },
            title = { Text(if (targetOn) "Nyalakan Kontak Motor?" else "Matikan Kontak Motor?") },
            text = {
                Text(
                    if (targetOn) "Relay kontak motor akan diaktifkan. Pastikan posisi aman dan standar siap."
                    else "Relay kontak motor akan diputus. Jangan lakukan saat melaju."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRelayTarget = null
                        val err = onSetContactRelay(targetOn)
                        if (err != null) onShowMessage(err)
                    },
                    modifier = Modifier.testTag("confirm_contact_relay_button")
                ) { Text("Konfirmasi") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRelayTarget = null }) { Text("Batal") }
            }
        )
    }

    if (showProfileDialog) {
        var rName by remember(s.riderName) { mutableStateOf(s.riderName) }
        var bName by remember(s.bikeName) { mutableStateOf(s.bikeName) }
        var pNum by remember(s.plateNumber) { mutableStateOf(s.plateNumber) }
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("Profil Pengendara & Motor") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = rName, onValueChange = { rName = it }, label = { Text("Nama Pengendara") })
                    OutlinedTextField(value = bName, onValueChange = { bName = it }, label = { Text("Nama Motor EV") })
                    OutlinedTextField(value = pNum, onValueChange = { pNum = it }, label = { Text("Nomor Polisi") })
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateSettings(
                            s.copy(
                                riderName = rName.ifBlank { s.riderName },
                                bikeName = bName.ifBlank { s.bikeName },
                                plateNumber = pNum.ifBlank { s.plateNumber }
                            )
                        )
                        showProfileDialog = false
                    },
                    modifier = Modifier.testTag("save_profile_dialog_button")
                ) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProfileDialog = false }) { Text("Batal") }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Garasi, Aksesori & Akun", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                FilledTonalButton(
                    onClick = onOpenMotovlogCam,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("garage_motovlog_button")
                ) {
                    Icon(Icons.Rounded.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Motovlog")
                }
            }
        }

        // Virtual Garage & Rider Identity
        item {
            val avatarExists = s.avatarPath.isNotEmpty() && File(s.avatarPath).exists()
            CockpitCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(106.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(activeLivery.bodyColor, activeLivery.trimColor.copy(alpha = 0.55f))
                            )
                        )
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.35f))
                                .clickable(onClick = onPickAvatarImage)
                                .testTag("pick_avatar_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarExists) {
                                Image(
                                    painter = rememberAsyncImagePainter(Uri.fromFile(File(s.avatarPath))),
                                    contentDescription = "Avatar Pengendara",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Rounded.ElectricMoped, contentDescription = "Pilih Foto", tint = activeLivery.trimColor)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.bikeName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${s.riderName} • ${s.plateNumber}", fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Livery: ${activeLivery.name}", fontSize = 11.sp, color = activeLivery.trimColor)
                        }
                        IconButton(
                            onClick = { showProfileDialog = true },
                            modifier = Modifier.testTag("edit_profile_button")
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Ubah Profil", tint = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    kGarageLiveries.forEach { g ->
                        FilterChip(
                            selected = s.garageStyleId == g.id,
                            onClick = { onUpdateSettings(s.copy(garageStyleId = g.id)) },
                            label = { Text(g.name) }
                        )
                    }
                }
            }
        }

        // Theme & Accent
        item {
            CockpitCard {
                Text("TEMA & AKSEN KOKPIT", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mode Gelap", modifier = Modifier.weight(1f))
                    Switch(
                        checked = s.isDarkTheme,
                        onCheckedChange = { onUpdateSettings(s.copy(isDarkTheme = it)) },
                        modifier = Modifier.testTag("dark_theme_switch")
                    )
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    kAccentPalettes.forEach { p ->
                        val sel = s.accentId == p.id
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(p.primary)
                                .clickable { onUpdateSettings(s.copy(accentId = p.id)) }
                                .testTag("accent_swatch_${p.id}"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (sel) {
                                Icon(Icons.Rounded.Check, contentDescription = p.name, tint = Color.Black, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }

        // ESP32 #1 Accessories
        item {
            CockpitCard {
                Text("AKSESORI ESP32 #1 (RELAY, MATRIX & AUDIO)", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                if (!esp1Ok) {
                    Text(
                        "ESP32 #1 belum terhubung. Kontrol akan aktif otomatis setelah terhubung.",
                        fontSize = 12.sp,
                        color = c.warning,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Kontak Motor (Relay)", modifier = Modifier.weight(1f))
                    FilledTonalButton(
                        enabled = esp1Ok,
                        onClick = {
                            pendingRelayTarget = !(esp1.contactOn ?: false)
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("esp1_contact_relay_button")
                    ) {
                        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (esp1.contactOn == true) "Matikan Kontak" else "Nyalakan Kontak")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LED Matrix Buntut", modifier = Modifier.weight(1f))
                    FilterChip(
                        selected = state.matrixAutoRotate,
                        enabled = esp1Ok,
                        onClick = { onToggleMatrixAutoRotate(!state.matrixAutoRotate) },
                        label = { Text("Rotasi") }
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = esp1.matrixOn == true,
                        enabled = esp1Ok,
                        onCheckedChange = onSetMatrixPower
                    )
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(0 to "Scroll", 1 to "Kedip", 2 to "Cepat").forEach { (eid, lbl) ->
                        FilterChip(
                            selected = matrixEffectId == eid,
                            onClick = { matrixEffectId = eid },
                            label = { Text(lbl) },
                            modifier = Modifier.testTag("matrix_effect_chip_$eid")
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = matrixText,
                        onValueChange = { matrixText = it },
                        label = { Text("Tambah teks LED Matrix") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (matrixText.isNotBlank()) {
                                onAddMatrixItem(matrixText, matrixEffectId)
                                matrixText = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("add_matrix_item_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Tambah Antrean Matrix", tint = c.accent)
                    }
                }
                if (state.matrixQueue.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.matrixQueue.forEach { mx ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = c.surfaceElevated,
                                border = BorderStroke(1.dp, c.borderSubtle),
                                modifier = Modifier.clickable(enabled = esp1Ok) { onSendMatrixItemNow(mx) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(mx.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    IconButton(
                                        onClick = { onDeleteMatrixItem(mx.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Hapus ${mx.text}", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(enabled = esp1Ok, onClick = { onSendEsp1Command("MUSIC_PREV") }) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Lagu Sebelumnya")
                    }
                    IconButton(enabled = esp1Ok, onClick = {
                        onSendEsp1Command(if (esp1.musicState == "PLAY") "MUSIC_PAUSE" else "MUSIC_PLAY")
                    }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Putar / Jeda")
                    }
                    IconButton(enabled = esp1Ok, onClick = { onSendEsp1Command("MUSIC_STOP") }) {
                        Icon(Icons.Rounded.Stop, contentDescription = "Berhenti")
                    }
                    IconButton(enabled = esp1Ok, onClick = { onSendEsp1Command("MUSIC_NEXT") }) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Lagu Berikutnya")
                    }
                }
                Text("Volume DFPlayer: ${esp1.musicVol}/30", fontSize = 12.sp, color = c.textMuted)
                Slider(
                    value = esp1.musicVol.toFloat(),
                    onValueChange = {
                        if (esp1Ok) onSendEsp1Command("MUSIC_VOL:${it.roundToInt()}")
                    },
                    valueRange = 0f..30f
                )
            }
        }

        // AT+MAPS Resolution Settings
        item {
            CockpitCard {
                Text("RESOLUSI LAYAR MID MOTOR (AT+MAPS)", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ukuran aktif: ${s.mapsSnapWidth} × ${s.mapsSnapHeight} px (maks 7000 karakter)",
                    fontSize = 12.sp,
                    color = c.textMuted
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(280 to 176, 350 to 220, 480 to 300, 320 to 320).forEach { (w, h) ->
                        FilterChip(
                            selected = s.mapsSnapWidth == w && s.mapsSnapHeight == h,
                            onClick = { onUpdateSettings(s.copy(mapsSnapWidth = w, mapsSnapHeight = h)) },
                            label = { Text("${w}x$h") }
                        )
                    }
                }
                Slider(
                    value = s.mapsSnapWidth.coerceIn(160, 480).toFloat(),
                    onValueChange = { onUpdateSettings(s.copy(mapsSnapWidth = it.roundToInt())) },
                    valueRange = 160f..480f
                )
                Slider(
                    value = s.mapsSnapHeight.coerceIn(120, 360).toFloat(),
                    onValueChange = { onUpdateSettings(s.copy(mapsSnapHeight = it.roundToInt())) },
                    valueRange = 120f..360f
                )
            }
        }

        // Calibration & Alarms
        item {
            CockpitCard {
                Text("KALIBRASI BATERAI, FISIKA & ALARM", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Spacer(Modifier.height(6.dp))
                Text("Faktor Kalibrasi Rute: ${String.format(Locale.US, "%.2f", s.routeCalFactor)}x", fontSize = 13.sp)
                Slider(
                    value = s.routeCalFactor.coerceIn(0.6, 1.5).toFloat(),
                    onValueChange = { onUpdateSettings(s.copy(routeCalFactor = it.toDouble())) },
                    valueRange = 0.6f..1.5f
                )
                Text("Referensi Efisiensi: ${s.refWhPerKm.roundToInt()} Wh/km", fontSize = 13.sp)
                Slider(
                    value = s.refWhPerKm.coerceIn(15.0, 50.0).toFloat(),
                    onValueChange = { onUpdateSettings(s.copy(refWhPerKm = it.toDouble())) },
                    valueRange = 15f..50f
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Suara Panduan Navigasi (TTS ID)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = s.voiceNavEnabled,
                        onCheckedChange = { onUpdateSettings(s.copy(voiceNavEnabled = it)) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Alarm Baterai & Suhu Aktif", modifier = Modifier.weight(1f))
                    Switch(
                        checked = s.alarmMasterOn,
                        onCheckedChange = { onUpdateSettings(s.copy(alarmMasterOn = it)) }
                    )
                }
            }
        }

        // AT+NAV Rotating Slots
        item {
            val sl = s.atNavSlots
            CockpitCard {
                Text("SLOT TEKS BERGANTIAN MID (AT+NAV)", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(sl.showRange, { onUpdateSettings(s.copy(atNavSlots = sl.copy(showRange = !sl.showRange))) }, { Text("Range") })
                    FilterChip(sl.showTrip, { onUpdateSettings(s.copy(atNavSlots = sl.copy(showTrip = !sl.showTrip))) }, { Text("Trip") })
                    FilterChip(sl.showEnergy, { onUpdateSettings(s.copy(atNavSlots = sl.copy(showEnergy = !sl.showEnergy))) }, { Text("Wh") })
                    FilterChip(sl.showEfficiency, { onUpdateSettings(s.copy(atNavSlots = sl.copy(showEfficiency = !sl.showEfficiency))) }, { Text("Wh/km") })
                    FilterChip(sl.showPower, { onUpdateSettings(s.copy(atNavSlots = sl.copy(showPower = !sl.showPower))) }, { Text("Daya") })
                    FilterChip(sl.showCo2, { onUpdateSettings(s.copy(atNavSlots = sl.copy(showCo2 = !sl.showCo2))) }, { Text("CO₂") })
                    FilterChip(sl.showLocation, { onUpdateSettings(s.copy(atNavSlots = sl.copy(showLocation = !sl.showLocation))) }, { Text("Lokasi") })
                    FilterChip(sl.showWeather, { onUpdateSettings(s.copy(atNavSlots = sl.copy(showWeather = !sl.showWeather))) }, { Text("Cuaca") })
                }
            }
        }

        // API Keys (Carto Maps & OpenChargeMap SPKLU)
        item {
            var cartoKeyDraft by remember(s.cartoApiKey) { mutableStateOf(s.cartoApiKey) }
            var ocmKeyDraft by remember(s.ocmApiKey) { mutableStateOf(s.ocmApiKey) }
            CockpitCard {
                Text("KUNCI API PETA & SPKLU", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cartoKeyDraft,
                    onValueChange = { cartoKeyDraft = it },
                    label = { Text("Carto Maps API Key") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("garage_carto_key_input")
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ocmKeyDraft,
                    onValueChange = { ocmKeyDraft = it },
                    label = { Text("OpenChargeMap API Key (SPKLU)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("garage_ocm_key_input")
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val cleanedCarto = TileCacheService.cleanCartoApiKey(cartoKeyDraft)
                        scope.launch {
                            TileCacheService.clearStyles(
                                context,
                                listOf("dark", "carto_voyager", "carto_positron")
                            )
                            onUpdateSettings(
                                s.copy(
                                    cartoApiKey = cleanedCarto,
                                    ocmApiKey = ocmKeyDraft.trim()
                                )
                            )
                            onShowMessage("API key peta & SPKLU tersimpan.")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("garage_save_api_keys_button")
                ) {
                    Text("Simpan API Key")
                }
            }
        }
    }
}
