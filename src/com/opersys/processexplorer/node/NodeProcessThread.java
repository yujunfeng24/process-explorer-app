package com.opersys.processexplorer.node;

import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.*;

/**
 * Simple process listener thread.
 */
public class NodeProcessThread extends Thread {

    private static final String TAG = "ProcessExplorer-NodeProcessThread";

    private AssetManager assetManager;
    private String dir;
    private String exec;
    private String js;

    private Handler msgHandler;
    private NodeService service;
    private Process nodeProcess;
    private ProcessBuilder nodeProcessBuilder;

    private SharedPreferences sharedPrefs;

    public void startProcess() {
        this.start();
    }

    public void endProcess() {
        if (nodeProcess != null)
            nodeProcess.destroy();

        nodeProcessBuilder = null;
        nodeProcess = null;
    }

    private void setMode(String modeStr, String path) throws IOException {
        ProcessBuilder chmodProcBuilder = new ProcessBuilder();
        Process chmodProc;

        chmodProcBuilder.command("chmod", modeStr, path);
        chmodProc = chmodProcBuilder.start();

        try {
            chmodProc.waitFor();
        } catch (InterruptedException e) {
            // FIXME: Not sure what to do here.
        }
    }

    public void extractAsset() {
        InputStream is;
        GzipCompressorInputStream gzis;
        TarArchiveInputStream tgzis;
        TarArchiveEntry tentry;

        //msgHandler.post(new Runnable() {
        //    @Override
        //public void run() {
        //        service.broadcastNodeServiceEvent(NodeServiceEvent.NODE_EXTRACTING);
        //    }
        //});

        try {
            is = assetManager.open("system-explorer.tgz");
            gzis = new GzipCompressorInputStream(is);
            tgzis = new TarArchiveInputStream(gzis);

            while ((tentry = tgzis.getNextTarEntry()) != null) {
                final File outputTarget = new File(dir, tentry.getName());

                if (tentry.isDirectory()) {
                    if (!outputTarget.exists()) {
                        if (!outputTarget.mkdirs()) {
                            String s = String.format("Couldn't create directory %s.", outputTarget.getAbsolutePath());
                            throw new IllegalStateException(s);
                        }
                    }
                } else {
                    final File parentTarget = new File(outputTarget.getParent());

                    // Make the parent directory if it doesn't exists.
                    if (!parentTarget.exists())
                    {
                        if (!parentTarget.mkdirs()) {
                            String s = String.format("Couldn't create directory %s.", parentTarget.toString());
                            throw new IllegalStateException(s);
                        }
                    }

                    final OutputStream outputFileStream = new FileOutputStream(outputTarget);
                    IOUtils.copy(tgzis, outputFileStream);
                    outputFileStream.close();
                }
            }
        } catch (IOException ex) {
            Log.e(TAG, "Asset decompression error", ex);
        }

        try {
            setMode("0777", dir + "/node");
        } catch (IOException e) {
            Log.e(TAG, "Failed to made node binary executable", e);
        }
    }

    @Override
    public void run() {
        BufferedReader bin, berr;
        final StringBuffer sin, serr;
        String s;

        Log.d(TAG, "Node process thread starting");

        msgHandler.post(new Runnable() {
            @Override
            public void run() {
                service.broadcastNodeServiceEvent(NodeService.EVENT_STARTING);
            }
        });

        nodeProcessBuilder = new ProcessBuilder()
                .directory(new File(dir))
                .command(exec, js);

        extractAsset();

        try {
            Log.d(TAG, "Node process thread started");

            msgHandler.post(new Runnable() {
                @Override
                public void run() {
                    service.broadcastNodeServiceEvent(NodeService.EVENT_STARTED);
                }
            });

            nodeProcess = nodeProcessBuilder.start();
            nodeProcess.waitFor();

            Log.d(TAG, "Node process thread stopping");

            sin = new StringBuffer();
            serr = new StringBuffer();

            // Read the outputs
            if (nodeProcess.getInputStream() != null) {
                bin = new BufferedReader(new InputStreamReader(nodeProcess.getInputStream()));
                while ((s = bin.readLine()) != null) sin.append(s);
            }
            if (nodeProcess.getErrorStream() != null) {
                berr = new BufferedReader(new InputStreamReader(nodeProcess.getErrorStream()));
                while ((s = berr.readLine()) != null) serr.append(s);
            }

            msgHandler.post(new Runnable() {
                @Override
                public void run() {
                    service.broadcastNodeServiceEvent(NodeService.EVENT_STOPPED);
                }
            });

        } catch (IOException e) {
            endProcess();

            msgHandler.post(new Runnable() {
                @Override
                public void run() {
                    service.broadcastNodeServiceEvent(NodeService.EVENT_ERROR);
                }
            });

        } catch (InterruptedException e) {
            endProcess();

            msgHandler.post(new Runnable() {
                @Override
                public void run() {
                    service.broadcastNodeServiceEvent(NodeService.EVENT_ERROR);
                }
            });
        } finally {
            endProcess();
        }
    }

    public NodeProcessThread(AssetManager assetManager,
                             String dir,
                             String execfile,
                             String jsfile,
                             Handler msgHandler,
                             NodeService service) {
        this.assetManager = assetManager;
        this.dir = dir;
        this.msgHandler = msgHandler;
        this.service = service;
        this.exec = dir + "/"+ execfile;
        this.js = dir + "/" + jsfile;
    }
}