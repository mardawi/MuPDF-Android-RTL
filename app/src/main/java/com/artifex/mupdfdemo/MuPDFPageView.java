package com.artifex.mupdfdemo;

import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;

import java.util.ArrayList;

/* This enum should be kept in line with the cooresponding C enum in mupdf.c */
enum SignatureState {
    NoSupport,
    Unsigned,
    Signed
}

abstract class PassClickResultVisitor {
    public abstract void visitText(PassClickResultText result);

    public abstract void visitChoice(PassClickResultChoice result);

    public abstract void visitSignature(PassClickResultSignature result);
}

class PassClickResult {
    public final boolean changed;

    public PassClickResult(boolean _changed) {
        changed = _changed;
    }

    public void acceptVisitor(PassClickResultVisitor visitor) {
    }
}

class PassClickResultText extends PassClickResult {
    public final String text;

    public PassClickResultText(boolean _changed, String _text) {
        super(_changed);
        text = _text;
    }

    public void acceptVisitor(PassClickResultVisitor visitor) {
        visitor.visitText(this);
    }
}

class PassClickResultChoice extends PassClickResult {
    public final String[] options;
    public final String[] selected;

    public PassClickResultChoice(boolean _changed, String[] _options, String[] _selected) {
        super(_changed);
        options = _options;
        selected = _selected;
    }

    public void acceptVisitor(PassClickResultVisitor visitor) {
        visitor.visitChoice(this);
    }
}

class PassClickResultSignature extends PassClickResult {
    public final SignatureState state;

    public PassClickResultSignature(boolean _changed, int _state) {
        super(_changed);
        state = SignatureState.values()[_state];
    }

    public void acceptVisitor(PassClickResultVisitor visitor) {
        visitor.visitSignature(this);
    }
}

public class MuPDFPageView extends PageView implements MuPDFView {
    private final MuPDFCore mCore;
    private AsyncTask<Void, Void, PassClickResult> mPassClick;
    private RectF mWidgetAreas[];
    private Annotation mAnnotations[];
    private int mSelectedAnnotationIndex = -1;
    private AsyncTask<Void, Void, RectF[]> mLoadWidgetAreas;
    private AsyncTask<Void, Void, Annotation[]> mLoadAnnotations;
    private AsyncTask<String, Void, Boolean> mSetWidgetText;
    private AsyncTask<String, Void, Void> mSetWidgetChoice;
    private AsyncTask<PointF[], Void, Void> mAddStrikeOut;
    private AsyncTask<PointF[][], Void, Void> mAddInk;
    private AsyncTask<Integer, Void, Void> mDeleteAnnotation;
    private Runnable changeReporter;

    public MuPDFPageView(Context c, MuPDFCore core, Point parentSize, Bitmap sharedHqBm) {
        super(c, parentSize, sharedHqBm);
        mCore = core;
    }


    public LinkInfo hitLink(float x, float y) {
        // Since link highlighting was implemented, the super class
        // PageView has had sufficient information to be able to
        // perform this method directly. Making that change would
        // make MuPDFCore.hitLinkPage superfluous.
        float scale = mSourceScale * (float) getWidth() / (float) mSize.x;
        float docRelX = (x - getLeft()) / scale;
        float docRelY = (y - getTop()) / scale;

        for (LinkInfo l : mLinks)
            if (l.rect.contains(docRelX, docRelY))
                return l;

        return null;
    }

    public void setChangeReporter(Runnable reporter) {
        changeReporter = reporter;
    }

    public Hit passClickEvent(float x, float y) {
        float scale = mSourceScale * (float) getWidth() / (float) mSize.x;
        final float docRelX = (x - getLeft()) / scale;
        final float docRelY = (y - getTop()) / scale;
        boolean hit = false;
        int i;

        if (mAnnotations != null) {
            for (i = 0; i < mAnnotations.length; i++)
                if (mAnnotations[i].contains(docRelX, docRelY)) {
                    hit = true;
                    break;
                }

            if (hit) {
                switch (mAnnotations[i].type) {
                    case HIGHLIGHT:
                    case UNDERLINE:
                    case SQUIGGLY:
                    case STRIKEOUT:
                    case INK:
                        mSelectedAnnotationIndex = i;
                        setItemSelectBox(mAnnotations[i]);
                        return Hit.Annotation;
                }
            }
        }

        mSelectedAnnotationIndex = -1;
        setItemSelectBox(null);

        if (!mCore.javascriptSupported())
            return Hit.Nothing;

        if (mWidgetAreas != null) {
            for (i = 0; i < mWidgetAreas.length && !hit; i++)
                if (mWidgetAreas[i].contains(docRelX, docRelY))
                    hit = true;
        }

        if (hit) {
            mPassClick = new AsyncTask<Void, Void, PassClickResult>() {
                @Override
                protected PassClickResult doInBackground(Void... arg0) {
                    return mCore.passClickEvent(mPageNumber, docRelX, docRelY);
                }

                @Override
                protected void onPostExecute(PassClickResult result) {
                    if (result.changed) {
                        changeReporter.run();
                    }
                }
            };

            mPassClick.execute();
            return Hit.Widget;
        }

        return Hit.Nothing;
    }

    @TargetApi(11)
    public boolean copySelection() {
        final StringBuilder text = new StringBuilder();

        processSelectedText(new TextProcessor() {
            StringBuilder line;

            public void onStartLine() {
                line = new StringBuilder();
            }

            public void onWord(TextWord word) {
                if (line.length() > 0)
                    line.append(' ');
                line.append(word.w);
            }

            public void onEndLine() {
                if (text.length() > 0)
                    text.append('\n');
                text.append(line);
            }
        });

        if (text.length() == 0)
            return false;

        int currentApiVersion = android.os.Build.VERSION.SDK_INT;
        if (currentApiVersion >= android.os.Build.VERSION_CODES.HONEYCOMB) {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);

            cm.setPrimaryClip(ClipData.newPlainText("MuPDF", text));
        } else {
            android.text.ClipboardManager cm = (android.text.ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setText(text);
        }

        deselectText();

        return true;
    }

    public boolean markupSelection(final Annotation.Type type) {
        final ArrayList<PointF> quadPoints = new ArrayList<PointF>();
        processSelectedText(new TextProcessor() {
            RectF rect;

            public void onStartLine() {
                rect = new RectF();
            }

            public void onWord(TextWord word) {
                rect.union(word);
            }

            public void onEndLine() {
                if (!rect.isEmpty()) {
                    quadPoints.add(new PointF(rect.left, rect.bottom));
                    quadPoints.add(new PointF(rect.right, rect.bottom));
                    quadPoints.add(new PointF(rect.right, rect.top));
                    quadPoints.add(new PointF(rect.left, rect.top));
                }
            }
        });

        if (quadPoints.size() == 0)
            return false;

        mAddStrikeOut = new AsyncTask<PointF[], Void, Void>() {
            @Override
            protected Void doInBackground(PointF[]... params) {
                addMarkup(params[0], type);
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                loadAnnotations();
                update();
            }
        };

        mAddStrikeOut.execute(quadPoints.toArray(new PointF[quadPoints.size()]));

        deselectText();

        return true;
    }

    public void deleteSelectedAnnotation() {
        if (mSelectedAnnotationIndex != -1) {
            if (mDeleteAnnotation != null)
                mDeleteAnnotation.cancel(true);

            mDeleteAnnotation = new AsyncTask<Integer, Void, Void>() {
                @Override
                protected Void doInBackground(Integer... params) {
                    mCore.deleteAnnotation(mPageNumber, params[0]);
                    return null;
                }

                @Override
                protected void onPostExecute(Void result) {
                    loadAnnotations();
                    update();
                }
            };

            mDeleteAnnotation.execute(mSelectedAnnotationIndex);

            mSelectedAnnotationIndex = -1;
            setItemSelectBox(null);
        }
    }

    public void deselectAnnotation() {
        mSelectedAnnotationIndex = -1;
        setItemSelectBox(null);
    }

    public boolean saveDraw() {
        PointF[][] path = getDraw();

        if (path == null)
            return false;

        if (mAddInk != null) {
            mAddInk.cancel(true);
            mAddInk = null;
        }
        mAddInk = new AsyncTask<PointF[][], Void, Void>() {
            @Override
            protected Void doInBackground(PointF[][]... params) {
                mCore.addInkAnnotation(mPageNumber, params[0]);
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                loadAnnotations();
                update();
            }

        };

        mAddInk.execute(getDraw());
        cancelDraw();

        return true;
    }


    @Override
    protected CancellableTaskDefinition<Void, Void> getDrawPageTask(final Bitmap bm, final int sizeX, final int sizeY,
                                                                    final int patchX, final int patchY, final int patchWidth, final int patchHeight) {
        return new MuPDFCancellableTaskDefinition<Void, Void>(mCore) {
            @Override
            public Void doInBackground(MuPDFCore.Cookie cookie, Void... params) {
                // Workaround bug in Android Honeycomb 3.x, where the bitmap generation count
                // is not incremented when drawing.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB &&
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH)
                    bm.eraseColor(0);
                mCore.drawPage(bm, mPageNumber, sizeX, sizeY, patchX, patchY, patchWidth, patchHeight, cookie);
                return null;
            }
        };

    }

    protected CancellableTaskDefinition<Void, Void> getUpdatePageTask(final Bitmap bm, final int sizeX, final int sizeY,
                                                                      final int patchX, final int patchY, final int patchWidth, final int patchHeight) {
        return new MuPDFCancellableTaskDefinition<Void, Void>(mCore) {

            @Override
            public Void doInBackground(MuPDFCore.Cookie cookie, Void... params) {
                // Workaround bug in Android Honeycomb 3.x, where the bitmap generation count
                // is not incremented when drawing.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB &&
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH)
                    bm.eraseColor(0);
                mCore.updatePage(bm, mPageNumber, sizeX, sizeY, patchX, patchY, patchWidth, patchHeight, cookie);
                return null;
            }
        };
    }

    @Override
    protected LinkInfo[] getLinkInfo() {
        return mCore.getPageLinks(mPageNumber);
    }

    @Override
    protected TextWord[][] getText() {
        return mCore.textLines(mPageNumber);
    }

    @Override
    protected void addMarkup(PointF[] quadPoints, Annotation.Type type) {
        mCore.addMarkupAnnotation(mPageNumber, quadPoints, type);
    }

    private void loadAnnotations() {
        mAnnotations = null;
        if (mLoadAnnotations != null)
            mLoadAnnotations.cancel(true);
        mLoadAnnotations = new AsyncTask<Void, Void, Annotation[]>() {
            @Override
            protected Annotation[] doInBackground(Void... params) {
                return mCore.getAnnoations(mPageNumber);
            }

            @Override
            protected void onPostExecute(Annotation[] result) {
                mAnnotations = result;
            }
        };

        mLoadAnnotations.execute();
    }

    @Override
    public void setPage(final int page, PointF size) {
        loadAnnotations();

        mLoadWidgetAreas = new AsyncTask<Void, Void, RectF[]>() {
            @Override
            protected RectF[] doInBackground(Void... arg0) {
                return mCore.getWidgetAreas(page);
            }

            @Override
            protected void onPostExecute(RectF[] result) {
                mWidgetAreas = result;
            }
        };

        mLoadWidgetAreas.execute();

        super.setPage(page, size);
    }

    public void setScale(float scale) {
        // This type of view scales automatically to fit the size
        // determined by the parent view groups during layout
    }

    @Override
    public void releaseResources() {
        if (mPassClick != null) {
            mPassClick.cancel(true);
            mPassClick = null;
        }

        if (mLoadWidgetAreas != null) {
            mLoadWidgetAreas.cancel(true);
            mLoadWidgetAreas = null;
        }

        if (mLoadAnnotations != null) {
            mLoadAnnotations.cancel(true);
            mLoadAnnotations = null;
        }

        if (mSetWidgetText != null) {
            mSetWidgetText.cancel(true);
            mSetWidgetText = null;
        }

        if (mSetWidgetChoice != null) {
            mSetWidgetChoice.cancel(true);
            mSetWidgetChoice = null;
        }

        if (mAddStrikeOut != null) {
            mAddStrikeOut.cancel(true);
            mAddStrikeOut = null;
        }

        if (mDeleteAnnotation != null) {
            mDeleteAnnotation.cancel(true);
            mDeleteAnnotation = null;
        }

        super.releaseResources();
    }
}
