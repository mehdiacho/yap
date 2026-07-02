package com.mehdiacho.yap.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.data.AccountClient
import com.mehdiacho.yap.data.AccountStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SubscriptionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<AccountStatus?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var redeeming by remember { mutableStateOf(false) }
    var redeemError by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loadError = null
            try { status = AccountClient.status() } catch (e: Exception) {
                loadError = e.message ?: "Couldn't load your plan"
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Subscription & Usage", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        when {
            loadError != null -> {
                Text(loadError!!, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { reload() }) { Text("Retry") }
            }
            status == null -> {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                val s = status!!
                CurrentPlanCard(s)
                Spacer(Modifier.height(14.dp))
                UsageCard(s)
                Spacer(Modifier.height(22.dp))

                // Redeem a code
                Text("REDEEM A CODE", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase(); redeemError = null },
                        placeholder = { Text("Promo code") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        enabled = code.isNotBlank() && !redeeming,
                        onClick = {
                            scope.launch {
                                redeeming = true; redeemError = null
                                try {
                                    AccountClient.redeem(code.trim())
                                    Toast.makeText(context, "Code applied 🎉", Toast.LENGTH_SHORT).show()
                                    code = ""
                                    reload()
                                } catch (e: Exception) {
                                    redeemError = e.message ?: "Couldn't redeem that code"
                                } finally { redeeming = false }
                            }
                        },
                    ) { Text(if (redeeming) "…" else "Apply") }
                }
                redeemError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }

                Spacer(Modifier.height(26.dp))
                Text("PLANS", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                PlanCompareCard(
                    name = "Free", current = s.plan == "free",
                    bullets = listOf("Unlimited recording on device", "30 min/month cloud transcription", "Cloud backup & sync"),
                )
                Spacer(Modifier.height(10.dp))
                PlanCompareCard(
                    name = "Pro", current = s.plan == "pro",
                    bullets = listOf("Everything in Free", "30 hours/month cloud transcription", "Priority processing"),
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text("Upgrade — payments coming soon")
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun CurrentPlanCard(s: AccountStatus) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer).padding(18.dp)
    ) {
        Text(
            s.plan.replaceFirstChar { it.uppercase() },
            fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        val sub = buildString {
            append("Your current plan")
            s.expiresAt?.let { append(" · until ").append(DateFormat.getDateInstance().format(Date(it))) }
            if (s.byokUnlocked) append(" · BYOK unlocked")
        }
        Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
    }
}

@Composable
private fun UsageCard(s: AccountStatus) {
    val usedMin = s.secondsUsed / 60.0
    val capMin = (s.secondsCap / 60).coerceAtLeast(1)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(18.dp)
    ) {
        Row {
            Text("Cloud transcription", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(
                "%.1f / %d min".format(usedMin, capMin),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { (s.secondsUsed.toFloat() / s.secondsCap).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text("Resets monthly · cycle ${s.cycle}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanCompareCard(name: String, current: Boolean, bullets: List<String>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            if (current) {
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("CURRENT", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        bullets.forEach {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
