package net.jspiner.epub_viewer.ui.reader

import android.content.Context
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import io.reactivex.BackpressureStrategy
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.BiFunction
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.SingleSubject
import net.jspiner.epub_viewer.dto.Epub
import net.jspiner.epub_viewer.dto.Page
import net.jspiner.epub_viewer.dto.PageInfo
import net.jspiner.epub_viewer.util.onLayoutLoaded
import net.jspiner.epubstream.dto.ItemRef
import java.io.File

class Paginator(val context: Context, val extractedEpub: Epub) {

    private val WORKER_NUM = 10

    private val deviceWidth: Int by lazy { context.resources.displayMetrics.widthPixels }
    private val deviceHeight: Int by lazy { context.resources.displayMetrics.heightPixels }

    fun calculatePage(): Single<PageInfo> {
        return Observable.fromIterable(extractedEpub.opf.spine.itemrefs.toList())
            .toFlowable(BackpressureStrategy.BUFFER)
            .parallel(WORKER_NUM)
            .runOn(Schedulers.computation())
            .map { toManifestItemPair(it) }
            .map { measurePageInWebView(it) }
            .sequential()
            .toMap({ it.first.idRef }, { it.second })
            .map { pageMap -> PageInfo.create(pageMap) }
    }

    private fun toManifestItemPair(itemRef: ItemRef): Pair<ItemRef, File> {
        val manifestItemList = extractedEpub.opf.manifest.items

        for (item in manifestItemList) {
            if (item.id == itemRef.idRef) {
                return itemRef to extractedEpub.extractedDirectory.resolve(item.href)
            }
        }
        throw RuntimeException("해당 itemRef 를 manifest 에서 찾을 수 없음 id : $itemRef")
    }

    private fun measurePageInWebView(pair: Pair<ItemRef, File>): Pair<ItemRef, Page> {
        fun heightToPageNum(height: Long) = Math.ceil(height.toDouble() / deviceHeight.toDouble()).toInt()

        val itemRef = pair.first
        val fileUrl = pair.second.toURI().toURL().toString()
        val contentHeightSubject = SingleSubject.create<Long>()

        return Single.create<WebView> { emitter -> emitter.onSuccess(WebView(context)) }
            .doOnSuccess { setUpWebView(it) }
            .doOnSuccess { addJavascriptCallback(it, contentHeightSubject) }
            .doOnSuccess { it.loadUrl(fileUrl) }
            .zipWith(contentHeightSubject, BiFunction { _: WebView, height: Long -> height })
            .map { height -> Page(height, heightToPageNum(height)) }
            .map { page -> itemRef to page }
            .subscribeOn(AndroidSchedulers.mainThread())
            .blockingGet()
    }

    private fun setUpWebView(webView: WebView) {
        webView.isVerticalScrollBarEnabled = true
        webView.settings.apply {
            builtInZoomControls = false
            javaScriptEnabled = true
            setSupportZoom(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                webView.loadUrl("javascript:AndroidFunction.resize(document.body.scrollHeight)")
            }
        }
        webView.onLayoutLoaded {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(deviceWidth, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(deviceHeight, View.MeasureSpec.EXACTLY)
            webView.measure(widthSpec, heightSpec)
            webView.layout(0, 0, deviceWidth, deviceHeight)
        }
    }

    private fun addJavascriptCallback(webView: WebView, subject: SingleSubject<Long>) {
        class AndroidBridge {
            @JavascriptInterface
            fun resize(height: Long) {
                val webViewHeight = height * context.resources.displayMetrics.density
                subject.onSuccess(webViewHeight.toLong())
            }
        }
        webView.addJavascriptInterface(AndroidBridge(), "AndroidFunction")
    }
}