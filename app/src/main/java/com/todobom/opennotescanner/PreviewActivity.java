package com.todobom.opennotescanner;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.todobom.opennotescanner.helpers.AppConstants;
import com.todobom.opennotescanner.helpers.DocumentsManager;
import com.todobom.opennotescanner.helpers.ImageSwipeAdapter;
import com.todobom.opennotescanner.helpers.OnImageSwipeListener;
import com.todobom.opennotescanner.helpers.OnSaveCompleteListener;
import com.todobom.opennotescanner.helpers.PdfCreator;
import com.todobom.opennotescanner.network.SaveOptionManager;

import org.parceler.Parcels;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import static com.todobom.opennotescanner.helpers.AppConstants.DRACOON;
import static com.todobom.opennotescanner.helpers.AppConstants.EMAIL;
import static com.todobom.opennotescanner.helpers.AppConstants.FILE_SUFFIX_JPG;
import static com.todobom.opennotescanner.helpers.AppConstants.FILE_SUFFIX_PDF;
import static com.todobom.opennotescanner.helpers.AppConstants.FILE_SUFFIX_PNG;
import static com.todobom.opennotescanner.helpers.AppConstants.FTP_SERVER;
import static com.todobom.opennotescanner.helpers.AppConstants.LOCAL;
import static com.todobom.opennotescanner.helpers.AppConstants.NEXTCLOUD;


public class PreviewActivity extends AppCompatActivity implements View.OnClickListener,
        DialogInterface.OnClickListener, OnSaveCompleteListener, AdapterView.OnItemSelectedListener,
        OnImageSwipeListener {

    private static final String TAG = "PreviewActivity";

    SharedPreferences sharedPref;
    private ImageButton saveBtn;
    private EditText fileNameEt;
    private TextView pageNumberTv;
    private DocumentsManager documentsManager;
    private String saveOption;
    private String fileFormat;
    private String pageSizePref;
    private String[] pageSizeValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        Intent intent = getIntent();
        documentsManager = Parcels.unwrap(intent.getParcelableExtra(AppConstants.DOCUMENTS_EXTRA_KEY));

        ViewPager2 previewImageVp2 = findViewById(R.id.pager_preview_images);
        ImageSwipeAdapter adapter = new ImageSwipeAdapter(this, documentsManager.getFileUris());
        previewImageVp2.setAdapter(adapter);

        ImageButton exitBtn = findViewById(R.id.ibt_preview_cancel);
        exitBtn.setOnClickListener(this);
        saveBtn = findViewById(R.id.ibt_preview_save);
        saveBtn.setOnClickListener(this);
        ImageButton retakeBtn = findViewById(R.id.ibt_preview_retake);
        retakeBtn.setOnClickListener(this);
        ImageButton addBtn = findViewById(R.id.ibt_preview_add);
        addBtn.setOnClickListener(this);
        pageNumberTv = findViewById(R.id.tv_preview_page_number);

        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        saveOption = sharedPref.getString(getString(R.string.pref_key_save_option), LOCAL);
        fileFormat = sharedPref.getString(getString(R.string.pref_key_file_format),
                AppConstants.FILE_SUFFIX_PDF);
        pageSizePref = sharedPref.getString(getString(R.string.pref_key_page_size),
                AppConstants.DEFAULT_PAGE_SIZE);
        pageSizeValues = AppConstants.PAGE_SIZE_VALUES;

        if (!fileFormat.equals(AppConstants.FILE_SUFFIX_PDF)) {
            addBtn.setVisibility(View.GONE);
            pageNumberTv.setVisibility(View.GONE);
        }

        setSaveBtn();
    }

    private void setSaveBtn() {
        if (saveOption.equals(DRACOON) || saveOption.equals(NEXTCLOUD) || saveOption.equals(FTP_SERVER)) {
            Log.d(TAG, "setSaveBtn: cloud icon");
            saveBtn.setImageDrawable(getDrawable(R.drawable.ic_cloud_green));
        } else if (saveOption.equals(EMAIL)) {
            saveBtn.setImageDrawable(getDrawable(R.drawable.ic_email_green));
            Log.d(TAG, "setSaveBtn: email icon");
        }
    }

    private void createSaveDialog() {
        // https://bhavyanshu.me/tutorials/create-custom-alert-dialog-in-android/08/20/2015/
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        final View dialogView = inflater.inflate(R.layout.save_dialog, null);
        dialogBuilder.setView(dialogView);

        fileNameEt = dialogView.findViewById(R.id.et_filename);
        String currentDate = SimpleDateFormat.getDateInstance().format(new Date());
        fileNameEt.setText(getString(R.string.file_name_default, currentDate));
        // pageSize can only be set if it is pdf
        if (fileFormat.equals(AppConstants.FILE_SUFFIX_PDF)) {
            Spinner pageSizeSpinner = dialogView.findViewById(R.id.sp_format_size);
            TextView pageSizeTv = dialogView.findViewById(R.id.tv_dialog_page_size_title);
            pageSizeTv.setVisibility(View.VISIBLE);
            pageSizeSpinner.setVisibility(View.VISIBLE);
            // https://developer.android.com/guide/topics/ui/controls/spinner
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                    R.array.page_size_entries, android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            pageSizeSpinner.setOnItemSelectedListener(this);
            pageSizeSpinner.setAdapter(adapter);
            pageSizeSpinner.setSelection(getPageSizePosition());
        }

        dialogBuilder.setTitle(R.string.save_dialog_message);
        dialogBuilder.setNegativeButton(android.R.string.cancel, this);
        if (saveOption.equals(EMAIL)) {
            dialogBuilder.setPositiveButton(R.string.save_dialog_positive_email, this);
        } else {
            dialogBuilder.setPositiveButton(R.string.save_dialog_positive, this);
        }

        TextView pageSize = dialogView.findViewById(R.id.tv_dialog_page_size);
        pageSize.setText(fileFormat);

        AlertDialog dialog = dialogBuilder.create();
        dialog.show();
    }

    private int getPageSizePosition() {
        for (int i = 0; i < pageSizeValues.length; i++) {
            if (pageSizePref.equals(pageSizeValues[i])) {
                return i;
            }
        }
        return 0;
    }

    private void saveDocument() {
        Log.d(TAG, "saveDocument: ");

        String fileName = getFileName();
        ArrayList<String> fileUris = documentsManager.getFileUris();
        String outputFile = fileUris.get(0);

        if (fileFormat.equals(AppConstants.FILE_SUFFIX_PDF)) {
            outputFile = documentsManager.createPdfTempFile(fileName);
            PdfCreator.makePdf(pageSizePref, outputFile, fileUris);
        }

        String saveOption = sharedPref.getString(getString(R.string.pref_key_save_option), LOCAL);
        String saveAddress = sharedPref.getString(getString(R.string.pref_key_save_address),
                AppConstants.DEFAULT_FOLDER_NAME);
        SaveOptionManager saveOptionManager = new SaveOptionManager(this, saveOption, saveAddress);
        saveOptionManager.saveFile(outputFile, fileName + fileFormat);
    }

    private String getFileName() {
        String fileName = fileNameEt.getText().toString().trim();
        // remove unnecessary file suffix
        if (fileName.endsWith(FILE_SUFFIX_PDF) || fileName.endsWith(FILE_SUFFIX_JPG) || fileName.endsWith(FILE_SUFFIX_PNG)) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private void startIntentToCameraActivity() {
        Intent intent = new Intent(this, OpenNoteScannerActivity.class);
        intent.putExtra(AppConstants.DOCUMENTS_EXTRA_KEY, Parcels.wrap(documentsManager));
        startActivity(intent);
        finish();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.ibt_preview_cancel:
                documentsManager = new DocumentsManager(getCacheDir());
                startIntentToCameraActivity();
                break;
            case R.id.ibt_preview_save:
                createSaveDialog();
                break;
            case R.id.ibt_preview_retake:
                retakeScan();
                break;
            case R.id.ibt_preview_add:
                startIntentToCameraActivity();
                break;
        }
    }

    private void retakeScan() {
        Log.d(TAG, "retakeScan: ");
        documentsManager.retakeScan();
        startIntentToCameraActivity();
    }

    @Override
    public void onClick(DialogInterface dialogInterface, int i) {
        switch (i) {
            case DialogInterface.BUTTON_NEGATIVE:
                dialogInterface.cancel();
                break;
            case DialogInterface.BUTTON_POSITIVE:
                saveDocument();
                break;
        }
    }

    @Override
    public void saveComplete(boolean isSuccessful) {
        Log.d(TAG, "completeUpload: ");
        if (isSuccessful) {
            Log.d(TAG, "showToast: success");
            if (saveOption.equals(EMAIL)) {
                Toast.makeText(this, R.string.save_success_message_email, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.save_success_message, Toast.LENGTH_LONG).show();
            }
        } else {
            Log.e(TAG, "showToast: fail");
            Toast.makeText(this, R.string.save_fail_message, Toast.LENGTH_LONG).show();
        }
        documentsManager = new DocumentsManager(getCacheDir());
        startIntentToCameraActivity();
    }

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        Log.d(TAG, "Spinner onItemSelected: i");
        pageSizePref = pageSizeValues[i];
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {
        Log.d(TAG, "onNothingSelected: ");
    }

    @Override
    public void onBackPressed() {
        retakeScan();
    }

    @Override
    public void onSwipeImage(int position) {
        Log.d(TAG, "onSwipeImage: " + position);
        pageNumberTv.setText(getString(R.string.preview_page_number, (position), documentsManager.getPageNumber()));
    }
}
