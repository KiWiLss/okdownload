/*
 * Copyright (C) 2017 Jacksgong(jacksgong.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.dreamtobe.okdownload.core.interceptor;

import android.support.annotation.NonNull;

import java.io.File;
import java.io.IOException;

import cn.dreamtobe.okdownload.OkDownload;
import cn.dreamtobe.okdownload.core.Util;
import cn.dreamtobe.okdownload.core.breakpoint.BlockInfo;
import cn.dreamtobe.okdownload.core.breakpoint.BreakpointInfo;
import cn.dreamtobe.okdownload.core.breakpoint.BreakpointStore;
import cn.dreamtobe.okdownload.core.connection.DownloadConnection;
import cn.dreamtobe.okdownload.core.download.DownloadChain;
import cn.dreamtobe.okdownload.core.exception.InterruptException;
import cn.dreamtobe.okdownload.core.file.MultiPointOutputStream;

import static cn.dreamtobe.okdownload.core.download.DownloadChain.CHUNKED_CONTENT_LENGTH;

public class BreakpointInterceptor implements Interceptor.Connect, Interceptor.Fetch {

    @Override
    public DownloadConnection.Connected interceptConnect(DownloadChain chain) throws IOException {
        final DownloadConnection.Connected connected = chain.processConnect();

        if (chain.getCache().isInterrupt()) {
            throw InterruptException.SIGNAL;
        }

        // handle first connect.
        if (chain.isOtherBlockPark()) {
            // only can on the first block.
            if (chain.getBlockIndex() != 0) throw new IOException();
            discardOldFileIfExist(chain.getInfo().getPath());

            final long contentLength = chain.getResponseContentLength();
            if (OkDownload.with().downloadStrategy().isSplitBlock(contentLength, connected)) {
                // split
                final int blockCount = OkDownload.with().downloadStrategy()
                        .determineBlockCount(chain.getTask(), contentLength, connected);
                splitBlock(blockCount, chain);
            }

            OkDownload.with().callbackDispatcher().dispatch().splitBlockEnd(chain.getTask(),
                    chain.getInfo());

            chain.unparkOtherBlock();
        }

        // update for connected.
        final BreakpointStore store = OkDownload.with().breakpointStore();
        if (!store.update(chain.getInfo())) {
            throw new IOException("Update store failed!");
        }

        return connected;
    }

    void splitBlock(int blockCount, DownloadChain chain) throws IOException {
        final long totalLength = chain.getResponseContentLength();
        if (blockCount < 1) {
            throw new IOException("Block Count from strategy determine must be larger than 0, "
                    + "the current one is " + blockCount);
        }

        final BreakpointInfo info = chain.getInfo();
        info.resetBlockInfos();
        final long eachLength = totalLength / blockCount;
        for (int i = 0; i < blockCount; i++) {
            final long startOffset = i * eachLength;
            final long contentLength;
            if (i == blockCount - 1) {
                // last block
                final long remainLength = totalLength % blockCount;
                contentLength = eachLength + remainLength;
            } else {
                contentLength = eachLength;
            }

            final BlockInfo blockInfo = new BlockInfo(startOffset, contentLength);
            info.addBlock(blockInfo);
        }
    }

    void discardOldFileIfExist(@NonNull String path) {
        final File oldFile = new File(path);
        if (oldFile.exists()) OkDownload.with().processFileStrategy().discardOldFile(oldFile);
    }

    @Override
    public long interceptFetch(DownloadChain chain) throws IOException {
        final long contentLength = chain.getResponseContentLength();
        final int blockIndex = chain.getBlockIndex();
        final BreakpointInfo info = chain.getInfo();
        final BlockInfo blockInfo = info.getBlock(blockIndex);
        final long blockLength = blockInfo.getContentLength();
        final boolean isMultiBlock = !info.isSingleBlock();
        final boolean isNotChunked = contentLength != CHUNKED_CONTENT_LENGTH;

        long rangeLeft = blockInfo.getRangeLeft();
        long fetchLength = 0;
        long processFetchLength;
        boolean isFirstBlockLenienceRule = false;

        while (true) {
            processFetchLength = chain.loopFetch();
            if (processFetchLength == -1) {
                break;
            }

            fetchLength += processFetchLength;
            if (isNotChunked && isMultiBlock && blockIndex == 0
                    && Util.isFirstBlockMeetLenienceFull(rangeLeft + fetchLength, blockLength)) {
                isFirstBlockLenienceRule = true;
                break;
            }
        }

        // finish
        chain.flushNoCallbackIncreaseBytes();
        final MultiPointOutputStream outputStream = chain.getOutputStream();
        outputStream.ensureSyncComplete(blockIndex);

        if (!isFirstBlockLenienceRule && isNotChunked) {
            // local persist data check.
            outputStream.inspectComplete(blockIndex);

            // response content length check.
            if (fetchLength != contentLength) {
                throw new IOException("Fetch-length isn't equal to the response content-length, "
                        + fetchLength + "!= " + contentLength);
            }
        }


        return fetchLength;
    }

}
