/*
 * Copyright (c) 2021 Rene F. van Ee
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License v2.0,
 * which accompanies this distribution.
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package com.imumotion.amoquette

import android.annotation.SuppressLint
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.imumotion.amoquette.broker.Action
import com.imumotion.amoquette.broker.BrokerService
import io.moquette.BrokerConstants
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import org.apache.commons.codec.digest.DigestUtils
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.*


enum class ServiceAction {
    UNDEFINED,
    STOPPED,
    STARTED,
    PROBING,
    STARTING,
    STOPPING
}

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    val LOGTAG = this.javaClass.name
    private val gson = Gson()

    private var backPressedTime = 0L
    var IP = " "
    var size = ""
    var port = ""
    private var serviceAction = ServiceAction.UNDEFINED


    fun processMap(map: Map<String, Map<String, Any>>) {
        launch {
            map.forEach { topicmap ->
                topicmap.value.forEach {
                    // Create id name by concatenating topic (stripped of $, with
                    // forward slashes / replaced by underscore _ and converted to lowercase)
                    // and the key from the topicmap:
                    val idName =
                        "${topicmap.key}_${it.key}".lowercase().replace("/", "_").replace("\$", "")

                    val id = resources.getIdentifier(idName, "id", packageName)
                    if (id != 0) {
                        val strvalue = if (it.value is Double) String.format("%.2f", it.value)
                        else it.value.toString()
                        findViewById<TextView>(id).text = strvalue
                    }
                }
            }
        }
    }

    // Actions
    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("ResourceAsColor")
    private fun startService() {
        // Retrieve preferences/settings
        val brokerProperties = PreferenceManager.getDefaultSharedPreferences(this)
         val pass =
             brokerProperties.getString("password", "password")
         val host =
             brokerProperties.getString("host", "192.168.5.10")
         val maxSize = brokerProperties.getString("netty.mqtt.message_size", "999999999")
         val wakeLock = brokerProperties.getString("wakelockduration", "2")

         val props = Properties()
         props.setProperty(
             BrokerConstants.PORT_PROPERTY_NAME,
             brokerProperties?.getString("port", "61613")
         )
         props.setProperty(BrokerConstants.NEED_CLIENT_AUTH, "ture")
         val username = brokerProperties.getString("username", "admin")
         val password = brokerProperties.getString("password", pass)

         if (password != null) {
             val sha256hex: String = DigestUtils.sha256Hex(password)
             val filename = "password.conf"
             val fileContents = "$username:$sha256hex"
             try {
                 openFileOutput(filename, MODE_PRIVATE).use { fos ->
                     fos.write(fileContents.toByteArray())
                     val file = File(filesDir, filename)
                     props.setProperty(
                         BrokerConstants.PASSWORD_FILE_PROPERTY_NAME,
                         file.absolutePath
                     )
                 }
             } catch (e: IOException) {
                 e.printStackTrace()
             }
         } else {
             Toast.makeText(this, "Unable to generate auth file", Toast.LENGTH_SHORT).show()
         }

         props.setProperty(BrokerConstants.HOST_PROPERTY_NAME, host)
         props.setProperty(
             BrokerConstants.WEB_SOCKET_PORT_PROPERTY_NAME,
             BrokerConstants.WEBSOCKET_PORT.toString()
         )

        val a = BrokerConstants.NETTY_MAX_BYTES_PROPERTY_NAME
         props.setProperty(a,maxSize)
         props.setProperty(BROKER_PROPERTY_WAKE_LOCK_DURATION, wakeLock)

        Intent(applicationContext, BrokerService::class.java).also {
            it.action = Action.START.name
            it.putExtra(CONST_JSONSTRINGDATA, gson.toJson(props).toString())

            startForegroundService(it)

            connStringTextView.text = "Connected"
            connStringTextView.setTextColor(R.color.green)
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("ResourceAsColor")
    private fun stopService() {
        // Stop the broker
        Intent(applicationContext, BrokerService::class.java).also {
            it.action = Action.STOP.name
            startForegroundService(it)
            connStringTextView.text = "DisConnected"
            connStringTextView.setTextColor(R.color.red)
        }
    }


    // Activity life cycle
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceAction = ServiceAction.UNDEFINED

        title = "AMoQueTTe Broker"


        val preferences = PreferenceManager.getDefaultSharedPreferences(this).all

        if (preferences.isEmpty()) {
            // Start settings activity
            getLocalIpAddress {
                size = "9999999"
                IP = "Not configured Yet"
                port = "8080"

                host_val.text = IP
                host_port.text = port
                startActivity(Intent(this, SettingsActivity::class.java))
            }

        } else {
            getLocalIpAddress {

                Log.d("Test", "Preferences values: $preferences")


                getLocalIpAddress {
                    host_val.text = IP
                }
                size = preferences.getValue("netty.mqtt.message_size").toString()
                port = preferences.getValue("port").toString()


                host_port.text = port

                val brokerButton = findViewById<ImageButton>(R.id.brokerActiveButton)
                val brokerDisableButton = findViewById<ImageButton>(R.id.brokerDisableButton)
                brokerButton.setOnClickListener {
                    brokerDisableButton.visibility = View.VISIBLE
                    brokerButton.visibility = View.GONE
                    startService()
                }

                brokerDisableButton.setOnClickListener {
                    brokerDisableButton.visibility = View.GONE
                    brokerButton.visibility = View.VISIBLE
                    stopService()
                }
            }
        }

    }


    override fun onStart() {
        super.onStart()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this).edit()

        Log.d("Test", "DEFAULT PREFERENCES: $prefs")
        prefs.putString(getString(R.string.host), IP)
        prefs.putString(getString(R.string.netty_mqtt_message_size), size)
        prefs.apply()
    }


    override fun onRestart() {
        super.onRestart()

        val preferences = PreferenceManager.getDefaultSharedPreferences(this).all

        size = preferences.getValue("netty.mqtt.message_size").toString()
        getString(R.string.netty_mqtt_message_size).replace("9999999", size, false)
        IP = preferences.getValue("host").toString()
        port = preferences.getValue("port").toString()

        host_val.text = IP
        host_port.text = port

        val prefs = PreferenceManager.getDefaultSharedPreferences(this).edit()
        prefs.putString(getString(R.string.host), IP)
        prefs.putString(getString(R.string.netty_mqtt_message_size), size)
        prefs.commit()

    }

    override fun onResume() {
        super.onResume()

        val preferences = PreferenceManager.getDefaultSharedPreferences(this).all

        size = preferences.getValue("netty.mqtt.message_size").toString()
        IP = preferences.getValue("host").toString()
        port = preferences.getValue("port").toString()

        host_val.text = IP
        host_port.text = port

        val prefs = PreferenceManager.getDefaultSharedPreferences(this).edit()
        prefs.putString(getString(R.string.host), IP)
        prefs.putString(getString(R.string.netty_mqtt_message_size), size)
        prefs.commit()


    }


    internal suspend fun displayBrokerConnectionString() {
        withContext(Dispatchers.IO) {
            val wifiMgr = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ipULong = wifiMgr.connectionInfo.ipAddress.toULong()
            val ipbytes = ShortArray(4) { i -> ((ipULong shr (i shl 3)) and 255u).toShort() }
            val ipstr = ipbytes.joinToString(separator = ".")

            val brokerProperties =
                PreferenceManager.getDefaultSharedPreferences(applicationContext).all
            val port = brokerProperties["port"]
            val connectionstring = "tcp://$ipstr:$port"

            val connStringTextView: TextView = findViewById(R.id.connStringTextView)
            connStringTextView.text = connectionstring
        }
    }

    // Options menu
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        // BackPressed twice within 2 seconds?
        if (backPressedTime + 2000L > System.currentTimeMillis()) {
            super.onBackPressed()
            finishAffinity()
        } else {
            Toast.makeText(baseContext, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
        backPressedTime = System.currentTimeMillis()
    }

    fun getLocalIpAddress(completion: () -> Unit) {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        IP = inetAddress.hostAddress.toString()
                        Log.d("IP", "IP ADDRESS: $IP")
                        completion()
//                        return inetAddress.getHostAddress()
                    }
                }
            }
        } catch (ex: SocketException) {
            ex.printStackTrace()
            completion()
        }
//        return null
    }

    fun getLocalIpAddress() {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        IP = inetAddress.hostAddress.toString()
                        Log.d("IP", "IP ADDRESS: $IP")

//                        return inetAddress.getHostAddress()
                    }
                }
            }
        } catch (ex: SocketException) {
            ex.printStackTrace()

        }
//        return null
    }


}