package co.yaqut.yaqutpdf;

import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.widget.RelativeLayout;

import com.artifex.mupdfdemo.AsyncTask;
import com.artifex.mupdfdemo.FilePicker;
import com.artifex.mupdfdemo.Hit;
import com.artifex.mupdfdemo.MuPDFCore;
import com.artifex.mupdfdemo.MuPDFPageAdapter;
import com.artifex.mupdfdemo.MuPDFReaderView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;


public class MainActivity extends ActionBarActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private MuPDFCore mCore;
    private MuPDFReaderView mDocView;
    private File mTempFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initCore();

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //noinspection ResultOfMethodCallIgnored
        mTempFile.delete();
    }

    private void initCore() {

        new AsyncTask<Void, Void, String>() {

            @Override
            protected String doInBackground(Void... params) {
                try {
                    mTempFile = File.createTempFile("cached", ".data", getCacheDir());
                    OutputStream out = new FileOutputStream(mTempFile);
                    InputStream in = getAssets().open("sample.pdf");
                    byte[] buff = new byte[2048];
                    int len;
                    while (-1 != (len = in.read(buff)))
                        out.write(buff, 0, len);
                    out.close();
                    in.close();
                    return mTempFile.getPath();
                } catch (Exception ex) {
                    Log.e(TAG, "Error opening file", ex);
                }
                return null;
            }

            @Override
            protected void onPostExecute(String tempFilePath) {
                createCore(tempFilePath);
            }
        }.execute();

    }

    private void createCore(String filePath) {
        try {
            mCore = new MuPDFCore(this, filePath);
            Log.i(TAG, "Core created successfully. PDF pages:" + mCore.countPages());

        } catch (Exception ex) {
            Log.e(TAG, "Error opening file " + filePath, ex);
            return;
        }

        mDocView = new MuPDFReaderView(this) {
            @Override
            protected void onMoveToChild(int i) {
                if (mCore == null)
                    return;
                Log.i(TAG, String.format("%d / %d", i + 1, mCore.countPages()));
                super.onMoveToChild(i);
            }

            @Override
            protected void onTapMainDocArea() {

                Log.i(TAG, "MuPDFReaderView onTapMainDocArea");
            }

            @Override
            protected void onDocMotion() {
                Log.i(TAG, "MuPDFReaderView onTapMainDocArea");
            }

            @Override
            protected void onHit(Hit item) {
                Log.i(TAG, "MuPDFReaderView onHit");

            }
        };

        mDocView.setAdapter(new MuPDFPageAdapter(this, mCore, MuPDFPageAdapter.DIRECTION_RTL));
        // For RTL. Start at the end. The adapter will take care of the rest
        mDocView.setDisplayedViewIndex(mCore.countPages() - 1);

        // Stick the document view and the buttons overlay into a parent view
        RelativeLayout layout = new RelativeLayout(this);
        layout.addView(mDocView);
        setContentView(layout);
    }

}
