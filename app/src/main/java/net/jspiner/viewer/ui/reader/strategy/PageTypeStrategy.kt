package net.jspiner.viewer.ui.reader.strategy

import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import net.jspiner.viewer.dto.LoadData
import net.jspiner.viewer.dto.LoadType
import net.jspiner.viewer.ui.reader.ReaderViewModel
import net.jspiner.viewer.ui.reader.viewer.EpubPagerAdapter
import net.jspiner.viewer.ui.reader.viewer.BiDirectionViewPager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

class PageTypeStrategy(viewModel: ReaderViewModel) : ViewerTypeStrategy(viewModel) {

    override fun changeViewPagerOrientation(biDirectionViewPager: BiDirectionViewPager) {
        biDirectionViewPager.horizontalMode()
        biDirectionViewPager.enableScroll()
    }

    override fun getAllPageCount(): Int = pageInfo.allPage

    override fun setCurrentPagerItem(pager: BiDirectionViewPager, adapter: EpubPagerAdapter, currentPage: Int) {
        pager.currentItem = currentPage
    }

    override fun onPagerItemSelected(pager: BiDirectionViewPager, adapter: EpubPagerAdapter, position: Int) {
        viewModel.setCurrentPage(position, false)

        readRawData(position)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { loadData ->
                viewModel.setLoadData(loadData)
            }
    }

    private fun readRawData(index: Int): Single<LoadData> {
        return Single.create<LoadData> { emitter ->
            var currentSpineIndex = -1
            for ((i, sumUntil) in pageInfo.pageCountSumList.withIndex()) {
                currentSpineIndex = i
                if (index < sumUntil) {
                    break
                }
            }

            val originFile = viewModel.toManifestItem(viewModel.extractedEpub.opf.spine.itemrefs[currentSpineIndex])
            val rawString = readFile(originFile)
            val bodyStart = rawString.indexOf("<body>") + "<body>".length
            val bodyEnd = rawString.indexOf("</body>")

            val emptyHtml = rawString.substring(0, bodyStart) + "%s" + rawString.substring(bodyEnd)
            val body = rawString.substring(bodyStart, bodyEnd)

            val innerPageIndex =
                index - if (currentSpineIndex == 0) 0 else pageInfo.pageCountSumList[currentSpineIndex - 1]
            val splitIndexList = pageInfo.spinePageList[currentSpineIndex].splitIndexList
            val splitStart = if (innerPageIndex == 0) 0 else splitIndexList[innerPageIndex - 1].toInt()
            val splitEnd = splitIndexList[innerPageIndex].toInt()
            val splitedText = body.split(" ").subList(splitStart, splitEnd).joinToString(" ")
            val res = String.format(emptyHtml, splitedText)

            emitter.onSuccess(
                LoadData(
                    LoadType.RAW,
                    originFile,
                    res
                )
            )
        }
    }

    private fun readFile(file: File): String {
        return BufferedReader(FileReader(file)).use { br ->
            val sb = StringBuilder()
            var line = br.readLine()

            while (line != null) {
                sb.append(line)
                sb.append(System.lineSeparator())
                line = br.readLine()
            }
            sb.toString()
        }
    }
}