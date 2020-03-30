package nl.tudelft.ipv8.android.demo.ui.bitcoin

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.android.synthetic.main.fragment_join_network.*
import kotlinx.coroutines.*
import nl.tudelft.ipv8.android.demo.CoinCommunity
import nl.tudelft.ipv8.android.demo.R
import nl.tudelft.ipv8.android.demo.sharedWallet.SWSignatureAskTransactionData
import nl.tudelft.ipv8.android.demo.sharedWallet.SWUtil
import nl.tudelft.ipv8.android.demo.ui.BaseFragment
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.util.toHex
import kotlin.concurrent.thread

/**
 * A simple [Fragment] subclass.
 * Use the [BitcoinFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class JoinNetworkFragment(
) : BaseFragment(R.layout.fragment_join_network) {
    private val tempBitcoinPk = ByteArray(2)
    private var adapter: SharedWalletListAdapter? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

//        var foundWallets = waitForCrawlAvailableSharedWallets().filter {
//            CoinCommunity.SW_TRANSACTION_BLOCK_KEYS.contains(it.type)
//        }.distinctBy {
//            SWUtil.parseTransaction(it.transaction).getString(CoinCommunity.SW_UNIQUE_ID)
//        }

//        Log.i("Coin", foundWallets.joinToString())

//        val sharedWalletBlocks = getCoinCommunity().discoverSharedWallets()
        loadSharedWallets()
    }

    private fun loadSharedWallets() {
        lifecycleScope.launchWhenStarted {
            var foundWallets = waitForCrawlAvailableSharedWallets().filter {
                CoinCommunity.SW_TRANSACTION_BLOCK_KEYS.contains(it.type)
            }.distinctBy {
                SWUtil.parseTransaction(it.transaction).getString(CoinCommunity.SW_UNIQUE_ID)
            }
            Log.i("Coin", "${foundWallets.size} peers unique shared wallets found")

            val publicKey = getTrustChainCommunity().myPeer.publicKey.keyToBin().toHex()

            adapter = SharedWalletListAdapter(
                this@JoinNetworkFragment,
                foundWallets,
                publicKey,
                "Click to join"
            )
            list_view.adapter = adapter
            list_view.setOnItemClickListener { _, view, position, id ->
                joinSharedWalletClicked(foundWallets[position])
                Log.i("Coin", "Clicked: $view, $position, $id")
            }
        }
    }

    private fun waitForCrawlAvailableSharedWallets(): ArrayList<TrustChainBlock> {
        var foundWallets: ArrayList<TrustChainBlock> = arrayListOf()
        runBlocking {
            //            withTimeout(TrustChainCrawler.CHAIN_CRAWL_TIMEOUT) {
            foundWallets = crawlAvailableSharedWallets()
//            }
        }
        return foundWallets
    }

    private suspend fun crawlAvailableSharedWallets(): ArrayList<TrustChainBlock> {
        val allPeers = getTrustChainCommunity().getPeers()
        Log.i("Coin", "${allPeers.size} peers found")
        val discoveredBlocks: ArrayList<TrustChainBlock> = arrayListOf()

        for (peer in allPeers) {
            discoveredBlocks.addAll(
                getTrustChainCommunity().sendCrawlRequest(
                    peer,
                    peer.publicKey.keyToBin(),
                    LongRange(-1, -1)
                )
            )
        }

        return discoveredBlocks
    }

    private fun joinSharedWalletClicked(block: TrustChainBlock) {
        val transactionPackage = getCoinCommunity().createBitcoinSharedWallet(block.calculateHash())
        val proposeBlock =
            getCoinCommunity().proposeJoinWalletOnTrustChain(
                block.calculateHash(),
                transactionPackage.serializedTransaction
            )

        // Wait until the new shared wallet is created
        fetchCurrentSharedWalletStatusLoop(transactionPackage.transactionId) // TODO: cleaner solution for blocking

        // Now start a thread to collect and wait (non-blocking) for signatures
        val requiredSignatures = proposeBlock.getData().SW_SIGNATURES_REQUIRED

        thread(start = true) {
            var finished = false
            while (!finished) {
                finished = collectJoinWalletSignatures(proposeBlock, requiredSignatures)
                Thread.sleep(100)
            }
        }

        getCoinCommunity().addSharedWalletJoinBlock(block.calculateHash())
    }

    /**
     * Collect the signatures of a join proposal. Returns true if enough signatures are found.
     */
    private fun collectJoinWalletSignatures(
        data: SWSignatureAskTransactionData,
        requiredSignatures: Int
    ): Boolean {
        val blockData = data.getData()
        val signatures =
            getCoinCommunity().fetchJoinSignatures(
                blockData.SW_UNIQUE_ID,
                blockData.SW_UNIQUE_PROPOSAL_ID
            )

        if (signatures.size >= requiredSignatures) {
            getCoinCommunity().safeSendingJoinWalletTransaction(data, signatures)
            return true
        }
        return false
    }

    private fun fetchCurrentSharedWalletStatusLoop(transactionId: String) {
        var finished = false

        while (!finished) {
            finished = getCoinCommunity().fetchBitcoinTransactionStatus(transactionId)
            Thread.sleep(1_000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_join_network, container, false)
    }

    companion object {
        @JvmStatic
        fun newInstance() = JoinNetworkFragment()
    }
}
