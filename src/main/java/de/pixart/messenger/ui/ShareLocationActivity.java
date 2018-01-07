package de.pixart.messenger.ui;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;

import de.pixart.messenger.R;
import de.pixart.messenger.utils.LocationHelper;

public class ShareLocationActivity extends LocationActivity implements LocationListener {

    LocationManager locationManager;
    private Location mLastLocation;
    private Button mCancelButton;
    private Button mShareButton;
    private String mLocationName;
    private RelativeLayout mSnackbar;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_locaction);
        if (getActionBar() != null) {
            getActionBar().setHomeButtonEnabled(true);
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        mLocationName = getString(R.string.me);

        mCancelButton = findViewById(R.id.cancel_button);
        mCancelButton.setOnClickListener(view -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        mShareButton = findViewById(R.id.share_button);
        mShareButton.setOnClickListener(view -> {
            if (mLastLocation != null) {
                Intent result = new Intent();
                result.putExtra("latitude", mLastLocation.getLatitude());
                result.putExtra("longitude", mLastLocation.getLongitude());
                result.putExtra("altitude", mLastLocation.getAltitude());
                result.putExtra("accuracy", (int) mLastLocation.getAccuracy());
                setResult(RESULT_OK, result);
                finish();
            }
        });

        mSnackbar = findViewById(R.id.snackbar);
        TextView snackbarAction = findViewById(R.id.snackbar_action);
        snackbarAction.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));

        requestLocationUpdates();
    }

    @Override
    protected void gotoLoc() throws UnsupportedOperationException {
        new getAddressAsync(this).execute();
    }

    @Override
    protected void setmLastLocation(Location location) {
        this.mLastLocation = location;
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isLocationEnabled()) {
            this.mSnackbar.setVisibility(View.GONE);
        } else {
            this.mSnackbar.setVisibility(View.VISIBLE);
        }
        setShareButtonEnabled(false);
    }

    @Override
    public void onLocationChanged(final Location location) {
        if (LocationHelper.isBetterLocation(location, this.mLastLocation)) {
            setShareButtonEnabled(true);
            this.mLastLocation = location;
            gotoLoc();
        }
    }

    @Override
    public void onStatusChanged(final String provider, final int status, final Bundle extras) {

    }

    @Override
    public void onProviderEnabled(final String provider) {

    }

    @Override
    public void onProviderDisabled(final String provider) {

    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private boolean isLocationEnabledKitkat() {
        try {
            final int locationMode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE);
            return locationMode != Settings.Secure.LOCATION_MODE_OFF;
        } catch (final Settings.SettingNotFoundException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isLocationEnabledLegacy() {
        final String locationProviders = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
        return !TextUtils.isEmpty(locationProviders);
    }

    private boolean isLocationEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT){
            return isLocationEnabledKitkat();
        } else {
            return isLocationEnabledLegacy();
        }
    }

    private void setShareButtonEnabled(final boolean enabled) {
        if (enabled) {
            this.mShareButton.setEnabled(true);
            this.mShareButton.setTextColor(0xff2e4272);
            this.mShareButton.setText(R.string.share);
        } else {
            this.mShareButton.setEnabled(false);
            this.mShareButton.setTextColor(0x8a000000);
            this.mShareButton.setText(R.string.locating);
            showLocation(null, null);
        }
    }

    private static String getAddress(Context context, Location location) {
        double longitude = location.getLongitude();
        double latitude = location.getLatitude();
        String address = "";
        if (latitude != 0 && longitude != 0) {
            Geocoder geoCoder = new Geocoder(context, Locale.getDefault());
            try {
                List<Address> addresses = geoCoder.getFromLocation(latitude, longitude, 1);
                if (addresses != null && addresses.size() > 0) {
                    Address Address = addresses.get(0);
                    StringBuilder strAddress = new StringBuilder("");

                    if (Address.getAddressLine(0).length() > 0) {
                        strAddress.append(Address.getAddressLine(0));
                    }
                    address = strAddress.toString().replace(", ", "<br>");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return address;
    }

    private class getAddressAsync extends AsyncTask<Void, Void, Void> {
        String address = null;

        private WeakReference<ShareLocationActivity> activityReference;

        getAddressAsync(ShareLocationActivity context) {
            activityReference = new WeakReference<>(context);
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            showLocation(mLastLocation, null);
        }

        @Override
        protected Void doInBackground(Void... params) {
            address = getAddress(ShareLocationActivity.this, mLastLocation);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
            showLocation(mLastLocation, address);
        }
    }

    private void showLocation (@Nullable Location location, @Nullable String address) {
        if (location == null && TextUtils.isEmpty(address)) { // no location and no address available
            final WebView webView = findViewById(R.id.webView);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.loadUrl("file:///android_asset/map.html");
        } else if (location != null && TextUtils.isEmpty(address)) { // location but no address available
            String LocationName = "<b>" + mLocationName + "</b>";
            final WebView webView = findViewById(R.id.webView);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.loadUrl("file:///android_asset/map.html?lat=" + mLastLocation.getLatitude() + "&lon=" + mLastLocation.getLongitude() + "&name=" + LocationName);
        } else if (location != null && !TextUtils.isEmpty(address)) { // location and address available
            String LocationName = "<b>" + mLocationName + "</b><br>" + address;
            final WebView webView = findViewById(R.id.webView);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.loadUrl("file:///android_asset/map.html?lat=" + mLastLocation.getLatitude() + "&lon=" + mLastLocation.getLongitude() + "&name=" + LocationName);
        }
    }
}
