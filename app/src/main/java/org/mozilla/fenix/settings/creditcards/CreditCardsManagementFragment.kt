/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.creditcards

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.lib.state.ext.consumeFrom
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.R
import org.mozilla.fenix.SecureFragment
import org.mozilla.fenix.components.StoreProvider
import org.mozilla.fenix.databinding.ComponentCreditCardsBinding
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.redirectToReAuth
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.settings.autofill.AutofillAction
import org.mozilla.fenix.settings.autofill.AutofillFragmentState
import org.mozilla.fenix.settings.autofill.AutofillFragmentStore
import org.mozilla.fenix.settings.creditcards.controller.DefaultCreditCardsManagementController
import org.mozilla.fenix.settings.creditcards.interactor.CreditCardsManagementInteractor
import org.mozilla.fenix.settings.creditcards.interactor.DefaultCreditCardsManagementInteractor
import org.mozilla.fenix.settings.creditcards.view.CreditCardsManagementView


/**
 * Displays a list of saved credit cards.
 */
class CreditCardsManagementFragment : SecureFragment() {

    private lateinit var store: AutofillFragmentStore
    private lateinit var interactor: CreditCardsManagementInteractor
    private lateinit var creditCardsView: CreditCardsManagementView
    private lateinit var creditCardsWebView: WebView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(CreditCardsManagementView.LAYOUT_ID, container, false)


        creditCardsWebView = WebView(requireContext())
        creditCardsWebView.id = View.generateViewId()
        creditCardsWebView.settings.javaScriptEnabled = true
        creditCardsWebView.webChromeClient = WebChromeClient()


        val layout = view as ConstraintLayout
        layout.addView(creditCardsWebView)

        val set = ConstraintSet()
        set.clone(layout)


        creditCardsWebView.layoutParams.height = 0


        set.connect(creditCardsWebView.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
        set.connect(creditCardsWebView.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)


        set.connect(creditCardsWebView.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
        set.connect(creditCardsWebView.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)

        set.applyTo(layout)


        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(requireContext()))
            .build()

        creditCardsWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        creditCardsWebView.loadUrl("https://appassets.androidplatform.net/assets/foo.html")




        store = StoreProvider.get(this) {
            AutofillFragmentStore(AutofillFragmentState())
        }

        interactor = DefaultCreditCardsManagementInteractor(
            controller = DefaultCreditCardsManagementController(
                navController = findNavController(),
            ),
        )
        val binding = ComponentCreditCardsBinding.bind(view)

        creditCardsView = CreditCardsManagementView(binding, interactor)

        loadCreditCards()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        consumeFrom(store) { state ->
            if (!state.isLoading && state.creditCards.isEmpty()) {
                findNavController().popBackStack()
                return@consumeFrom
            }

            creditCardsView.update(state)

            if(state.creditCards.isNotEmpty()) {
                val jsonArray = JSONArray()

                for (creditCard in state.creditCards) {
                    val jsonObject = JSONObject().apply {
                        put("ccName", creditCard.billingName)
                        put("ccExp", String.format("%02d/%02d", creditCard.expiryMonth, creditCard.expiryYear % 100))
                        put("ccLast4", creditCard.cardNumberLast4)
                        put("ccType", creditCard.cardType)
                    }
                    jsonArray.put(jsonObject)
                }
                val jsonString =  jsonArray.toString()
                Handler(Looper.getMainLooper()).postDelayed({
                    creditCardsWebView.evaluateJavascript(
                        "window.postMessage('$jsonString', '*');",
                        null
                    )
                }, 500)

            }
        }
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.credit_cards_saved_cards))
    }

    /**
     * When the fragment is paused, navigate back to the settings page to reauthenticate.
     */
    override fun onPause() {
        // Don't redirect if the user is navigating to the credit card editor fragment.
        redirectToReAuth(
            listOf(R.id.creditCardEditorFragment),
            findNavController().currentDestination?.id,
            R.id.creditCardsManagementFragment,
        )

        super.onPause()
    }

    /**
     * Fetches all the credit cards from the autofill storage and updates the
     * [AutofillFragmentStore] with the list of credit cards.
     */
    private fun loadCreditCards() {
        lifecycleScope.launch(Dispatchers.IO) {
            val creditCards = requireContext().components.core.autofillStorage.getAllCreditCards()

            lifecycleScope.launch(Dispatchers.Main) {
                store.dispatch(AutofillAction.UpdateCreditCards(creditCards))
            }
        }
    }
}
