package com.revitvisualizer.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.revitvisualizer.app.VisualizerViewModel
import com.revitvisualizer.app.advanced.Place
import com.revitvisualizer.app.advanced.PlaceSearch
import com.revitvisualizer.app.advanced.SunPosition
import com.revitvisualizer.app.advanced.Weather
import com.revitvisualizer.app.advanced.ManualPlaceDraft
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

@Composable
fun AdvancedControls(model: VisualizerViewModel) {
    val settings = model.environment
    val context = LocalContext.current
    val links = LocalUriHandler.current
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Advanced", style = MaterialTheme.typography.titleMedium)
            Text("Optional · location, sun, weather & snow", style = MaterialTheme.typography.labelSmall)
        }
        Switch(settings.enabled, { model.updateEnvironment(settings.copy(enabled = it)) },
            Modifier.semantics { contentDescription = "Enable Advanced" })
    }
    if (settings.enabled) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Use weather and snow on their own. Place and north arrow are optional; selecting both enables calculated sunlight and a separate paid orientation analysis.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(model.placeQuery, model::updatePlaceQuery, label = { Text("Place anywhere in the world") },
            placeholder = { Text("New York, Bø i Telemark, Sahara…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(model::findPlaces, enabled = model.placeQuery.trim().length >= 2) { Text("Find place") }
            TextButton(model::openManualPlace) { Text("Use coordinates") }
        }
        model.placeCandidates.forEach { candidate ->
            TextButton({ model.choosePlace(candidate) }, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(candidate.name)
                    Text(candidate.coordinates, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val place = settings.place
        if (place != null) {
            Text(place.name, style = MaterialTheme.typography.titleSmall)
            Text("${place.coordinates} · ${place.zone.id}", style = MaterialTheme.typography.labelSmall)
            if (place.atPole) Text("At the pole, north is not unique. The arrow uses local north approached along longitude ${place.longitude}°. UTC is the default time convention.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(model::openManualPlace) { Text("Adjust coordinates / time zone") }
            TextButton({ model.updatePlaceQuery("") }) { Text("Remove location") }
        } else Text("No location selected. Weather and snow can still be used.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({
                DatePickerDialog(context, { _, year, month, day ->
                    model.updateEnvironment(settings.copy(date = LocalDate.of(year, month + 1, day), offset = null))
                }, settings.date.year, settings.date.monthValue - 1, settings.date.dayOfMonth).apply {
                    datePicker.minDate = LocalDate.of(1900, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    datePicker.maxDate = LocalDate.of(2100, 12, 31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.show()
            }) { Text("Date: ${settings.date}") }
            TextButton({
                TimePickerDialog(context, { _, hour, minute ->
                    model.updateEnvironment(settings.copy(time = LocalTime.of(hour, minute), offset = null))
                }, settings.time.hour, settings.time.minute, true).show()
            }) { Text("Time: ${settings.time}") }
        }
        Text("Date and time apply to sunlight only when a location and confirmed north arrow are selected. Time is local to that place, including daylight saving time.", style = MaterialTheme.typography.labelSmall)
        val offsets = if (settings.usesSolarOrientation) settings.offsets() else emptyList()
        if (offsets.size > 1) {
            Text("This time occurs twice. Choose which UTC offset:", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offsets.forEach { offset ->
                    FilterChip(settings.offset == offset, { model.updateEnvironment(settings.copy(offset = offset)) }, label = { Text("UTC$offset") })
                }
            }
        }
        if (place != null && settings.northConfirmed) {
            val time = runCatching { settings.localDateTime() }
            val local = time.getOrNull()
            if (local == null) Text(time.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else {
                val sun = remember(place, local) { SunPosition.calculate(place, local) }
                Text("${sun.daylight.label} · ${String.format(Locale.ROOT, "%.2f", sun.apparentElevation)}° elevation" +
                    if (sun.directionDefined) " · ${String.format(Locale.ROOT, "%.2f", sun.azimuth)}° from north" else " · horizontal bearing undefined",
                    style = MaterialTheme.typography.bodySmall)
                Text("UTC${local.offset} · ${local.toInstant()}", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("Weather", style = MaterialTheme.typography.titleSmall)
        Weather.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { weather -> FilterChip(settings.weather == weather,
                    { model.updateEnvironment(settings.copy(weather = weather)) }, label = { Text(weather.label) }) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Snow cover", Modifier.weight(1f))
            Switch(settings.snow, { model.updateEnvironment(settings.copy(snow = it)) }, Modifier.semantics { contentDescription = "Snow cover" })
        }
        Button(model::openNorthEditor, Modifier.fillMaxWidth()) {
            Text(if (settings.northConfirmed) "Adjust north arrow" else "Place north arrow")
        }
        Text(if (settings.northConfirmed) "North arrow confirmed. Changing the crop or perspective requires confirmation again."
            else "Optional: place the arrow on the prepared image and point N toward geographic north in the scene.", style = MaterialTheme.typography.bodySmall)
        if (settings.northConfirmed) TextButton(model::removeNorth) { Text("Remove north arrow") }
        Text("Sun position assumes an unobstructed horizon and standard atmosphere. AI interprets the arrow and scene perspective; inspect the generated shadows for accuracy.", style = MaterialTheme.typography.labelSmall)
        Text(if (settings.usesSolarOrientation) "Location and north arrow selected: image previews are sent to OpenAI for a paid orientation analysis before one generated image. This adds cost and waiting time. Unclear orientation stops the process before image generation."
            else "One generated image will be requested. No orientation analysis or north-arrow guide will be sent. A location on its own describes the environment; an arrow on its own does not set sunlight.", style = MaterialTheme.typography.labelSmall)
        Text("Place search uses Open-Meteo / GeoNames and device geocoding. Location describes the natural environment, without adding landmarks.", style = MaterialTheme.typography.labelSmall)
        TextButton({ links.openUri("https://open-meteo.com/en/docs/geocoding-api") }) { Text("Place data: Open-Meteo / GeoNames") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
    model.manualPlaceDraft?.let {
        ManualPlaceDialog(it, model::updateManualPlaceDraft, model::dismissManualPlace, model::useManualPlace)
    }
}

@Composable
private fun ManualPlaceDialog(draft: ManualPlaceDraft, onChange: (ManualPlaceDraft) -> Unit, onDismiss: () -> Unit, onUse: (Place) -> Unit) {
    val (name, latitude, longitude, zone, initial, error) = draft
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Precise location") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { onChange(draft.copy(name = it.take(200))) }, label = { Text("Place / environment name") }, singleLine = true)
            OutlinedTextField(latitude, { onChange(draft.copy(latitude = it.take(24))) }, label = { Text("Latitude (−90 to 90)") }, singleLine = true)
            OutlinedTextField(longitude, { onChange(draft.copy(longitude = it.take(24))) }, label = { Text("Longitude (−180 to 180)") }, singleLine = true)
            OutlinedTextField(zone, { onChange(draft.copy(zone = it.take(80))) }, label = { Text("IANA time zone or UTC") }, placeholder = { Text("Europe/Oslo") }, singleLine = true)
            Text("Use a decimal point. East and north are positive. Examples: Europe/Oslo, America/New_York, Africa/Algiers, UTC. At a pole, longitude sets the reference meridian.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton({
            val lat = latitude.trim().toDoubleOrNull()
            val lon = longitude.trim().toDoubleOrNull()
            val timeZone = runCatching { ZoneId.of(zone.trim()) }.getOrNull()
            if (name.isBlank() || lat == null || lon == null || !PlaceSearch.validCoordinates(lat, lon) || timeZone == null) {
                onChange(draft.copy(error = "Enter a name, valid coordinates and a recognized time zone."))
            } else onUse(Place(name.trim(), lat, lon, timeZone,
                if (initial?.latitude == lat && initial.longitude == lon) initial.elevation else 0.0))
        }) { Text("Use location") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
