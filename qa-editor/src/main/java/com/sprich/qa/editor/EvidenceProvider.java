package com.sprich.qa.editor;
import android.content.*;import android.database.*;import android.net.Uri;
/** Only controlled QA text is exposed; this APK must never be distributed as Sprich. */
public final class EvidenceProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){ MatrixCursor c=new MatrixCursor(new String[]{"snapshot"});c.addRow(new Object[]{EditorActivity.snapshot});return c; }
    public String getType(Uri uri){return "application/json";}
    public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
}
