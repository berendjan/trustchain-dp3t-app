package nl.tudelft.trustchain.trader.ui

import android.bluetooth.BluetoothManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.core.content.getSystemService
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import nl.tudelft.ipv8.*
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.android.keyvault.AndroidCryptoProvider
import nl.tudelft.ipv8.android.messaging.bluetooth.BluetoothLeEndpoint
import nl.tudelft.ipv8.android.messaging.bluetooth.GattServerManager
import nl.tudelft.ipv8.android.messaging.bluetooth.IPv8BluetoothLeAdvertiser
import nl.tudelft.ipv8.android.messaging.udp.AndroidUdpEndpoint
import nl.tudelft.ipv8.keyvault.PrivateKey
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.messaging.EndpointAggregator
import nl.tudelft.ipv8.peerdiscovery.Network
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomWalk
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.BaseActivity
import nl.tudelft.trustchain.trader.MarketCommunity
import nl.tudelft.trustchain.trader.R
import java.net.InetAddress

class TrustChainTraderActivity : BaseActivity() {
    override val navigationGraph = R.navigation.nav_graph_trader
    override val bottomNavigationMenu = R.menu.bottom_navigation_menu_trader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appBarConfiguration = AppBarConfiguration.Builder(
            setOf(
                R.id.transferFragment,
                R.id.traderFragment,
                R.id.peerFragment,
                R.id.AI_HistoryFragment
            )
        ).build()
        setupActionBarWithNavController(
            findNavController(R.id.navHostFragment),
            appBarConfiguration
        )
        createMarketBot()
    }

    fun createMarketBot() {
        val ipv8 = create()
        if (!ipv8.isStarted()){
            ipv8.start()
        }
    }

    fun create(): IPv8 {
        val privateKey = getPrivateKey()
        val configuration = IPv8Configuration(
            overlays = listOf(
                createMarketCommunity()
            ), walkerInterval = 5.0
        )
        val connectivityManager = application.getSystemService<ConnectivityManager>()
            ?: throw IllegalStateException("ConnectivityManager not found")

        val udpEndpoint = AndroidUdpEndpoint(8090, InetAddress.getByName("0.0.0.0"),
            connectivityManager)

        val bluetoothManager = application.getSystemService<BluetoothManager>()
            ?: throw IllegalStateException("BluetoothManager not found")

        val myPeer = Peer(privateKey)
        val network = Network()

        val gattServer = GattServerManager(application, myPeer)
        val bleAdvertiser = IPv8BluetoothLeAdvertiser(bluetoothManager)
        val bluetoothEndpoint = if (bluetoothManager.adapter != null)
            BluetoothLeEndpoint(application, bluetoothManager, gattServer, bleAdvertiser,
                network) else null

        val endpointAggregator = EndpointAggregator(
            udpEndpoint,
            bluetoothEndpoint
        )

        return IPv8(endpointAggregator, configuration, myPeer, AndroidCryptoProvider, network)
    }

    private fun getPrivateKey(): PrivateKey {
        // Load a key from the shared preferences
        val key = "Market_bot_key"
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val privateKey = prefs.getString(key, null)
        return if (privateKey == null) {
            // Generate a new key on the first launch
            val newKey = AndroidCryptoProvider.generateKey()
            prefs.edit()
                .putString(key, newKey.keyToBin().toHex())
                .apply()
            newKey
        } else {
            AndroidCryptoProvider.keyFromPrivateBin(privateKey.hexToBytes())
        }
    }

    private fun createMarketCommunity(): OverlayConfiguration<MarketCommunity> {
        val randomWalk = RandomWalk.Factory()
        return OverlayConfiguration(
            Overlay.Factory(MarketCommunity::class.java),
            listOf(randomWalk)
        )
    }

}
