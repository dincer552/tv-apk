package com.dincer552.tvremote

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val bg = Color(0xFFF0F0F0)
private val keys = listOf("Power","Source","Menu","Home","Number 1","Back","Up","Down","Left","OK","Right","CH+","CH-","VOL+","VOL-","More","Mute")
data class IrCommand(val frequency:Int,val pattern:String,val repeat:Int=1)

class MainActivity : ComponentActivity() {
    private lateinit var ir: ConsumerIrManager
    private val prefs by lazy { getSharedPreferences("tv_profile", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        ir = getSystemService(CONSUMER_IR_SERVICE) as ConsumerIrManager
        setContent {
            MaterialTheme {
                var lab by remember { mutableStateOf(false) }
                var selected by remember { mutableStateOf("Power") }
                var frequency by remember { mutableStateOf("38000") }
                var raw by remember { mutableStateOf("") }
                var repeats by remember { mutableStateOf("1") }
                var status by remember { mutableStateOf(if(ir.hasIrEmitter()) "IR vericisi hazır" else "IR vericisi bulunamadı") }
                var mappings by remember { mutableStateOf(load()) }
                fun send(key:String) {
                    val cmd=mappings[key]
                    status=if(cmd==null) "$key: henüz eşlenmedi" else transmit(cmd) ?: "$key komutu gönderildi"
                }
                Surface(Modifier.fillMaxSize(),color=bg) {
                    if(!lab) {
                        Column(Modifier.fillMaxSize().padding(horizontal=22.dp, vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                Text("‹",fontSize=32.sp)
                                Text("Samsung Televizyon",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                                Text("⋮",fontSize=26.sp,modifier=Modifier.clickable { lab=true })
                            }
                            Spacer(Modifier.height(26.dp))
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                                RoundKey("⏻",Color(0xFF20A879)){send("Power")}
                                RoundKey("⇥"){send("Source")}
                                RoundKey("☰"){send("Menu")}
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                                RoundKey("⌂"){send("Home")}
                                RoundKey("①"){send("Number 1")}
                                RoundKey("↶"){send("Back")}
                            }
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.size(260.dp).background(Color.White,CircleShape),contentAlignment=Alignment.Center) {
                                Box(Modifier.size(92.dp).background(bg,CircleShape).clickable { send("OK") },contentAlignment=Alignment.Center){Text("OK",fontSize=20.sp)}
                                Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceBetween) {
                                    Box(Modifier.size(72.dp).clickable { send("Up") })
                                    Box(Modifier.size(72.dp).clickable { send("Down") })
                                }
                                Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                    Box(Modifier.size(72.dp).clickable { send("Left") })
                                    Box(Modifier.size(72.dp).clickable { send("Right") })
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                PillKeys("⌃","CH","⌄",{send("CH+")},{send("CH-")})
                                PillKeys("+","VOL","−",{send("VOL+")},{send("VOL-")})
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                RoundKey("•••"){send("More")}
                                RoundKey("🔇"){send("Mute")}
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(status,fontSize=12.sp,color=Color.DarkGray)
                            TextButton(onClick={lab=true}){Text("Tuş ayarları / IR test laboratuvarı")}
                        }
                    } else {
                        Column(Modifier.fillMaxSize().padding(18.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("‹",fontSize=32.sp,modifier=Modifier.clickable { lab=false })
                                Spacer(Modifier.width(10.dp))
                                Text("IR Test Laboratuvarı",fontSize=21.sp,fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(status,fontSize=12.sp,color=Color.DarkGray)
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=!expanded}) {
                                OutlinedTextField(selected,{selected=it},readOnly=true,label={Text("Kumanda tuşu")},modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)})
                                ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                                    keys.forEach { k -> DropdownMenuItem(text={Text(k)},onClick={
                                        selected=k; expanded=false
                                        mappings[k]?.let { frequency=it.frequency.toString(); raw=it.pattern; repeats=it.repeat.toString() } ?: run { frequency="38000";raw="";repeats="1" }
                                    }) }
                                }
                            }
                            OutlinedTextField(frequency,{frequency=it.filter(Char::isDigit)},label={Text("Taşıyıcı frekansı (Hz), ör. 38000")},modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(raw,{raw=it},label={Text("Ham IR süreleri (µs): mark,space,mark,space…")},modifier=Modifier.fillMaxWidth(),minLines=3)
                            OutlinedTextField(repeats,{repeats=it.filter(Char::isDigit)},label={Text("Tekrar sayısı")},modifier=Modifier.fillMaxWidth())
                            Text("Örnek: 4500,4500,560,560,560,1690,560,560",fontSize=11.sp,color=Color.Gray)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(onClick={
                                    val c=parse(frequency,raw,repeats)
                                    status=if(c==null) "Frekans veya ham süreler geçersiz" else transmit(c) ?: "Test sinyali gönderildi; TV'yi kontrol et."
                                },modifier=Modifier.weight(1f)){Text("TEST ET")}
                                OutlinedButton(onClick={
                                    val c=parse(frequency,raw,repeats)
                                    if(c==null) status="Önce geçerli frekans ve ham süre gir" else {
                                        mappings=mappings+(selected to c);save(mappings);status="$selected komutu kabul edildi ve kaydedildi"
                                    }
                                },modifier=Modifier.weight(1f)){Text("KABUL ET")}
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Kabul edilen eşlemeler",fontWeight=FontWeight.Bold)
                            keys.forEach { k -> mappings[k]?.let { c ->
                                Text("✓ $k — ${c.frequency} Hz — ${c.pattern.take(38)}…",fontSize=12.sp,modifier=Modifier.fillMaxWidth().clickable {
                                    selected=k;frequency=c.frequency.toString();raw=c.pattern;repeats=c.repeat.toString()
                                }.padding(vertical=3.dp))
                            } }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick={lab=false}){Text("Kumandaya dön")}
                        }
                    }
                }
            }
        }
    }

    private fun parse(f:String,p:String,r:String):IrCommand? {
        val hz=f.toIntOrNull() ?: return null
        val values=p.split(',', ' ', '\n','\t').filter{it.isNotBlank()}.map{it.toIntOrNull() ?: return null}
        if(hz !in 20000..60000 || values.size<4 || values.size%2!=0 || values.any{it !in 1..100000}) return null
        return IrCommand(hz,values.joinToString(","),(r.toIntOrNull()?:1).coerceIn(1,10))
    }
    private fun transmit(c:IrCommand):String? {
        if(!ir.hasIrEmitter()) return "IR vericisi bulunamadı"
        val data=c.pattern.split(',').map{it.toInt()}.toIntArray()
        return try { repeat(c.repeat){ir.transmit(c.frequency,data)}; null }
        catch(e:Exception) { "IR gönderim hatası: ${e.message ?: "bilinmeyen hata"}" }
    }
    private fun load():Map<String,IrCommand> {
        val o=JSONObject(prefs.getString("commands","{}") ?: "{}")
        return keys.mapNotNull { k -> if(!o.has(k)) null else {
            val x=o.getJSONObject(k); k to IrCommand(x.optInt("frequency",38000),x.optString("pattern"),x.optInt("repeat",1))
        } }.toMap()
    }
    private fun save(m:Map<String,IrCommand>) {
        val o=JSONObject()
        m.forEach { (k,v)->o.put(k,JSONObject().put("frequency",v.frequency).put("pattern",v.pattern).put("repeat",v.repeat)) }
        prefs.edit().putString("commands",o.toString()).apply()
    }
}

@Composable private fun RoundKey(label:String,color:Color=Color(0xFF222222),click:()->Unit) {
    Box(Modifier.size(66.dp).background(Color.White,CircleShape).clickable(onClick=click),contentAlignment=Alignment.Center){Text(label,fontSize=23.sp,color=color)}
}
@Composable private fun PillKeys(top:String,mid:String,bottom:String,onTop:()->Unit,onBottom:()->Unit) {
    Column(Modifier.width(82.dp).height(148.dp).background(Color.White,RoundedCornerShape(42.dp)).padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceBetween) {
        Text(top,fontSize=24.sp,modifier=Modifier.clickable(onClick=onTop))
        Text(mid,fontSize=15.sp)
        Text(bottom,fontSize=24.sp,modifier=Modifier.clickable(onClick=onBottom))
    }
}
