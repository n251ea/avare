/*
Copyright (c) 2012, Zubair Khan (governer@gmail.com) 
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
    *     * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
    *
    *     THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.ds.avare;

import java.io.File;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.Timer;
import java.util.TimerTask;
import com.ds.avare.R;

import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;
import android.graphics.Color;

/**
 * @author zkhan
 * Main activity
 */
public class MainActivity extends Activity implements LocationListener, Observer, android.location.GpsStatus.Listener{

    /**
     * This view display location on the map.
     */
    private LocationView mLocationView;
    /**
     * Shows warning message about Avare
     */
    private AlertDialog mAlertDialogWarn;
    /**
     * Shows warning about GPS
     */
    private AlertDialog mGpsWarnDialog;
    /**
     * GPS manager
     */
    private LocationManager mLocationManager;
    /**
     * Current destination info
     */
    private Destination mDestination;
    /**
     * A timer that clicks to check GPS status
     */
    private Timer mTimer;
    /**
     * Time of last GPS message
     */
    private long mGpsLastUpdate;
    /**
     * Service that keeps state even when activity is dead
     */
    private StorageService mService;

    /**
     * App preferences
     */
    private Preferences mPreferences;

    private Location mLastLocation;
    
    private int mGpsPeriod;
    
    private SatelliteView mSatelliteView;
    
    private static final int GPS_PERIOD_LONG_MS = 8000;
    
    /* (non-Javadoc)
     * @see android.app.Activity#onCreate(android.os.Bundle)
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        mPreferences = new Preferences(this);

        LayoutInflater layoutInflater = (LayoutInflater)getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = layoutInflater.inflate(R.layout.slider, null);
        setContentView(view);
        mLocationView = (LocationView)view.findViewById(R.id.location);
        mSatelliteView = (SatelliteView)view.findViewById(R.id.satellites);

        /*
         * Start service now, bind later. This will be no-op if service is already running
         */
        Intent intent = new Intent(this, StorageService.class);
        startService(intent);

        if(mPreferences.isGpsUpdatePeriodShort()) {
            mGpsPeriod = 0;
        }
        else {
            mGpsPeriod = GPS_PERIOD_LONG_MS;
        }
        /*
         * Start GPS
         */
        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        if(null != mLocationManager) {
                
            if(isGpsAvailable()) {
                mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        mGpsPeriod / 4, 0, this);
                mLocationManager.addGpsStatusListener(this);
            }
            /*
             * Also obtain GSM based locations
             */
            mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 
                    0, 0, this);
        }
        
        
        /*
         * Throw this in case GPS is disabled.
         */
        if(mPreferences.shouldGpsWarn() && (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))) {
            mGpsWarnDialog = new AlertDialog.Builder(MainActivity.this).create();
            mGpsWarnDialog.setTitle(getString(R.string.GPSEnable));
            mGpsWarnDialog.setButton(getString(R.string.Yes),  new DialogInterface.OnClickListener() {
                /* (non-Javadoc)
                 * @see android.content.DialogInterface.OnClickListener#onClick(android.content.DialogInterface, int)
                 */
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    Intent i = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    startActivity(i);
                }
            });
            mGpsWarnDialog.setButton2(getString(R.string.No),  new DialogInterface.OnClickListener() {
                /* (non-Javadoc)
                 * @see android.content.DialogInterface.OnClickListener#onClick(android.content.DialogInterface, int)
                 */
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });
            mGpsWarnDialog.show();
        }        
        
    }
    
    /** Defines callbacks for service binding, passed to bindService() */
    /**
     * 
     */
    private ServiceConnection mConnection = new ServiceConnection() {

        /* (non-Javadoc)
         * @see android.content.ServiceConnection#onServiceConnected(android.content.ComponentName, android.os.IBinder)
         */
        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            /* 
             * We've bound to LocalService, cast the IBinder and get LocalService instance
             */
            StorageService.LocalBinder binder = (StorageService.LocalBinder)service;
            mService = binder.getService();

            if(mService.getDBResource().isOpen()) {
            	mService.getDBResource().close();
            }
            mService.getDBResource().open(mPreferences.mapsFolder() + "/" + getString(R.string.DatabaseName));
            if(!mService.getDBResource().isOpen()) {
                /*
                 * If could not open database then bring up download activity.
                 */
                startActivity(new Intent(MainActivity.this, ChartsDownloadActivity.class));
                return;
            }

            /*
             * Now get all stored data
             */
            mDestination = mService.getDestination();
            mLocationView.updateDestination(mDestination);
            if(mService.getGpsParams() == null) {
                /*
                 * Go to last known location till GPS locks.
                 */
                Location l = getLastLocation();
                if(null != l) {
                    mService.setGpsParams(new GpsParams(l));
                }
            }
            mLocationView.initParams(mService.getGpsParams(), mService);                
            
            /*
             * Go to last known location till GPS locks.
             */
            Location l = getLastLocation();
            if(null != l) {
                mLocationView.updateParams(new GpsParams(l));
            }
            
            /*
             * Show avare warning when service says so 
             */
            if(mService.shouldWarn()) {
             
                mAlertDialogWarn = new AlertDialog.Builder(MainActivity.this).create();
                mAlertDialogWarn.setTitle(getString(R.string.WarningMsg));
                mAlertDialogWarn.setMessage(getString(R.string.Warning));
                mAlertDialogWarn.setCanceledOnTouchOutside(true);
                mAlertDialogWarn.setCancelable(true);
                mAlertDialogWarn.setButton(getString(R.string.OK),  new DialogInterface.OnClickListener() {
                    /* (non-Javadoc)
                     * @see android.content.DialogInterface.OnClickListener#onClick(android.content.DialogInterface, int)
                     */
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
    
                mAlertDialogWarn.show();
            }
        }

        /* (non-Javadoc)
         * @see android.content.ServiceConnection#onServiceDisconnected(android.content.ComponentName)
         */
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
        }
    };

    /* (non-Javadoc)
     * @see android.app.Activity#onStart()
     */
    @Override
    protected void onStart() {
        super.onStart();
    }

    /* (non-Javadoc)
     * @see android.app.Activity#onResume()
     */
    @Override
    public void onResume() {
        super.onResume();
        
        if(mPreferences.shouldScreenStayOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);            
        }

        if(mPreferences.isPortrait()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);            
        }
        else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        mGpsLastUpdate = SystemClock.uptimeMillis();
        mTimer = new Timer();
        TimerTask gpsTime = new UpdateGps();
        /*
         * Give some delay for check start
         */
        mTimer.scheduleAtFixedRate(gpsTime, (GPS_PERIOD_LONG_MS * 2),
                GPS_PERIOD_LONG_MS / 4);

        /*
         * Registering our receiver
         * Bind now.
         */
        Intent intent = new Intent(this, StorageService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        
    }

    /**
     * 
     * @return
     */
    private boolean isGpsAvailable() {
        LocationManager lm = (LocationManager)getSystemService(Context.LOCATION_SERVICE);  
        List<String> providers = lm.getProviders(true);
        for (int i = providers.size() - 1; i >= 0; i--) {
            if(providers.get(i).equals(LocationManager.GPS_PROVIDER)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 
     * @return
     */
    private Location getLastLocation() {
        LocationManager lm = (LocationManager)getSystemService(Context.LOCATION_SERVICE);  
        List<String> providers = lm.getProviders(true);

        Location l = null;
        for (int i = providers.size() - 1; i >= 0; i--) {
            l = lm.getLastKnownLocation(providers.get(i));
            if (l != null) {
                break;
            }
        }
        if(null != l) {
            mLastLocation = l;
        }
        return l;
    }
    
    /** Determines whether one Location reading is better than the current Location fix
     * @param location  The new Location that you want to evaluate
     * @param currentBestLocation  The current Location fix, to which you want to compare the new one
     */
    private boolean isBetterLocation(Location location, Location currentBestLocation) {
       
       final int TWO_MINUTES = 1000 * 60 * 2;
       
       if (currentBestLocation == null) {
           // A new location is always better than no location
           return true;
       }

       // Check whether the new location fix is newer or older
       long timeDelta = location.getTime() - currentBestLocation.getTime();
       boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
       boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
       boolean isNewer = timeDelta > 0;

       // If it's been more than two minutes since the current location, use the new location
       // because the user has likely moved
       if (isSignificantlyNewer) {
           // If the new location is more than two minutes older, it must be worse
           return true;
       } 
       else if (isSignificantlyOlder) {
           return false;
       }

       // Check whether the new location fix is more or less accurate
       int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
       boolean isLessAccurate = accuracyDelta > 0;
       boolean isMoreAccurate = accuracyDelta < 0;
       boolean isSignificantlyLessAccurate = accuracyDelta > 200;

       // Check if the old and new location are from the same provider
       boolean isFromSameProvider = isSameProvider(location.getProvider(),
               currentBestLocation.getProvider());

       // Determine location quality using a combination of timeliness and accuracy
       if (isMoreAccurate) {
           return true;
       } 
       else if (isNewer && !isLessAccurate) {
           return true;
       } 
       else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
           return true;
       }
       return false;
   }

   /** Checks whether two providers are the same */
   private boolean isSameProvider(String provider1, String provider2) {
       if (provider1 == null) {
         return provider2 == null;
       }
       return provider1.equals(provider2);
   }
    
    /* (non-Javadoc)
     * @see android.app.Activity#onPause()
     */
    @Override
    protected void onPause() {
        super.onPause();
        
        /*
         * Clean up on pause that was started in on resume
         */
        unbindService(mConnection);
        mTimer.cancel();

        if(null != mAlertDialogWarn) {
            try {
                mAlertDialogWarn.dismiss();
            }
            catch (Exception e) {
            }
        }
        
        mLocationView.freeTiles();
        
    }
    
    /* (non-Javadoc)
     * @see android.app.Activity#onRestart()
     */
    @Override
    protected void onRestart() {
        super.onRestart();
    }

    /* (non-Javadoc)
     * @see android.app.Activity#onStop()
     */
    @Override
    protected void onStop() {
        super.onStop();
    }

    /* (non-Javadoc)
     * @see android.app.Activity#onDestroy()
     */
    @Override
    public void onDestroy() {
        if(null != mGpsWarnDialog) {
            try {
                mGpsWarnDialog.dismiss();
            }                
            catch (Exception e) {   
            }
        }

        if(null != mLocationManager) {
            mLocationManager.removeUpdates(this);
            mLocationManager.removeGpsStatusListener(this);
        }

        super.onDestroy();
    }

    /* (non-Javadoc)
     * @see android.location.LocationListener#onLocationChanged(android.location.Location)
     */
    @Override
    public void onLocationChanged(Location location) {
        if (location != null && mService != null && (!mPreferences.isSimulationMode())) {

            mSatelliteView.updateLocation(location);

            /*
             * Called by GPS. Update everything driven by GPS.
             */
            if(isBetterLocation(location, mLastLocation)) {
                mLastLocation = location;
            }
            GpsParams params = new GpsParams(mLastLocation); 
            
            if(mDestination != null) {
                mDestination.updateTo(params);
            }
            mGpsLastUpdate = SystemClock.uptimeMillis();
            /*
             * Store GPS last location in case activity dies, we want to start from same loc
             */
            mLocationView.updateParams(params); 
            mService.setGpsParams(params);
            
            /*
             * Update distances/bearing to all airports in the area
             */
            mService.getArea().updateLocation(params);
            
        }
    }
    
    /* (non-Javadoc)
     * @see android.app.Activity#onCreateOptionsMenu(android.view.Menu)
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu){
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }


    /* (non-Javadoc)
     * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item){
        switch(item.getItemId()) {
        
            case R.id.nearest:
                
                if(null == mService) {
                    return false;
                }
                
                /*
                 * Put list view in a dialog
                 */
                final Dialog dlgn = new Dialog(this);
                ListView vn = new ListView(this);

                int airportnum = mService.getArea().getAirportsNumber();
                if(0 == airportnum) {
                    Toast.makeText(MainActivity.this, getString(R.string.AreaNF), 
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                final String [] airport = new String[airportnum];
                final String [] airportname = new String[airportnum];
                final String [] dist = new String[airportnum];
                final String [] bearing = new String[airportnum];
                final String [] fuel = new String[airportnum];
                final Integer[] color = new Integer[airportnum];

                for(int id = 0; id < airportnum; id++) {
                    airport[id] = mService.getArea().getAirport(id).getId();
                    airportname[id] = mService.getArea().getAirport(id).getName() + "(" + 
                            mService.getArea().getAirport(id).getId() + ")";
                    fuel[id] = mService.getArea().getAirport(id).getFuel();
                    dist[id] = "" + (float)(Math.round(mService.getArea().getAirport(id).getDistance() * 10) / 10) + " nm";
                    bearing[id] = "" + Math.round(mService.getArea().getAirport(id).getBearing()) + '\u00B0';
                    color[id] = WeatherHelper.metarSquare(mService.getArea().getAirport(id).getWeather());
                }

                vn.setAdapter(new NearestAdapter(this, dist, airportname, bearing, fuel, color));
                vn.setClickable(true);
                vn.setDividerHeight(10);
                vn.setCacheColorHint(Color.WHITE);
                vn.setBackgroundColor(Color.WHITE);
                vn.setOnItemClickListener(new OnItemClickListener() {

                    /* (non-Javadoc)
                     * @see android.content.DialogInterface.OnClickListener#onClick(android.content.DialogInterface, int)
                     */

                    @Override
                    public void onItemClick(AdapterView<?> arg0, View arg1,
                            int position, long id) {
                        String dst = airport[position];
                        mDestination = new Destination(dst, mPreferences, mService.getDBResource());
                        mDestination.addObserver(MainActivity.this);
                        mDestination.find();
                        dlgn.dismiss();
                    }
                });
                
                dlgn.setTitle(getString(R.string.Nearest));
                dlgn.setContentView(vn);
                dlgn.show();
                break;

            case R.id.destinationafd:
                
                /*
                 * Present a list view of all fields about a destination
                 */
                if(null == mDestination) {
                    Toast.makeText(MainActivity.this, getString(R.string.ValidDest), 
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                if(!mDestination.isFound()) {
                    Toast.makeText(MainActivity.this, getString(R.string.ValidDest), 
                            Toast.LENGTH_SHORT).show();
                    return false;
                }                
                       
                Intent intent0 = new Intent(MainActivity.this, PlatesActivity.class);
                startActivity(intent0);
                
                break;
                
            case R.id.pref:
                /*
                 * Bring up preferences
                 */
                startActivity(new Intent(this, PrefActivity.class));
                break;
                
            case R.id.help:
                Intent intent = new Intent(this, WebActivity.class);
                intent.putExtra("url", NetworkHelper.getHelpUrl());
                startActivity(intent);
                break;

            case R.id.download:
                startActivity(new Intent(MainActivity.this, ChartsDownloadActivity.class));
                break;
                
            case R.id.newdestination:
                if(null == mService.getDBResource()) {
                    return false;
                }
                if(!mService.getDBResource().isOpen()) {
                    return false;
                }
                
                /*
                 * Query new destination
                 * Present an alert dialog with a text field
                 */

                final AlertDialog dialogd = new AlertDialog.Builder(this).create();
                dialogd.setTitle(getString(R.string.DestinationPrompt));
                
                LayoutInflater inflater = getLayoutInflater();
                View dv = inflater.inflate(R.layout.destination_layout, (ViewGroup)getCurrentFocus());
                dialogd.setView(dv);

                /*
                 *  limit FAA/ICAO code length to 4
                 */
                final AutoCompleteTextView tv = (AutoCompleteTextView)dv.findViewById(R.id.destautoCompleteTextView);
                tv.setImeOptions(EditorInfo.IME_ACTION_DONE);
                tv.setText(mPreferences.getBase());
                tv.selectAll();
                tv.setThreshold(0);
                final ArrayAdapter<String> adapter = new ArrayAdapter<String>(MainActivity.this,
                        R.layout.input_field, mPreferences.getRecent());

                tv.setAdapter(adapter);
                tv.setOnTouchListener(new View.OnTouchListener(){
                    @Override
                    public boolean onTouch(View view, MotionEvent motionEvent) {
                        /*
                         * Clear hint when user touches the edit box
                         */
                        tv.setText("");
                        return false;
                    }
                });

                tv.setOnItemClickListener(new OnItemClickListener() {

                    @Override
                    public void onItemClick(AdapterView<?> parent, View arg1, int pos,
                            long id) {
                        String dst = adapter.getItem(pos);
                        mDestination = new Destination(dst, mPreferences, mService.getDBResource());
                        mDestination.addObserver(MainActivity.this);
                        mDestination.find();
                        dialogd.dismiss();
                    }
                });


                Button ok = (Button)dv.findViewById(R.id.destbuttonOK);
                ok.setOnClickListener(new View.OnClickListener() {

                    @Override
                    public void onClick(View arg0) {
                        String dst = tv.getText().toString();
                        mDestination = new Destination(dst, mPreferences, mService.getDBResource());
                        mDestination.addObserver(MainActivity.this);
                        mDestination.find();
                        dialogd.dismiss();
                        
                    }
                });

                Button cancel = (Button)dv.findViewById(R.id.destbuttonCancel);
                cancel.setOnClickListener(new View.OnClickListener() {

                    @Override
                    public void onClick(View arg0) {
                        dialogd.dismiss();
                    }
                });

                dialogd.show();

                break;
        }
        return true;
    }
        
    
    /**
     * @author zkhan
     *
     */
    private class UpdateGps extends TimerTask {
        
        /* (non-Javadoc)
         * @see java.util.TimerTask#run()
         */
        public void run() {
            /*
             *  No GPS signal
             *  Tell location view to show GPS status
             */
            if(null == mService) {
                mLocationView.updateErrorStatus(getString(R.string.Init));
            }
            else if(!mService.getDBResource().isOpen()) {
                mLocationView.updateErrorStatus(getString(R.string.LoadingMaps));
            }
            else if(!(new File(mPreferences.mapsFolder() + "/tiles")).exists()) {
                mLocationView.updateErrorStatus(getString(R.string.MissingMaps));
                startActivity(new Intent(MainActivity.this, ChartsDownloadActivity.class));
            }
            else if(mPreferences.isSimulationMode()) {
                mLocationView.updateErrorStatus(getString(R.string.SimulationMode));                
            }
            else if(mPreferences.shouldGpsWarn() && (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))) {
                /*
                 * Prompt user to enable GPS.
                 */
                mLocationView.updateErrorStatus(getString(R.string.GPSEnable)); 
            }
            else if((SystemClock.uptimeMillis() - mGpsLastUpdate) > 
                    (GPS_PERIOD_LONG_MS * 2)) {
                mLocationView.updateErrorStatus(getString(R.string.GPSLost));
            }
            else {
                /*
                 *  GPS kicking.
                 */
                mLocationView.updateErrorStatus(null);
            }
        }
    }

    @Override
    public void update(Observable arg0, Object arg1) {
        /*
         * Destination found?
         */
        if(arg0 instanceof Destination) {
            Boolean result = (Boolean)arg1;
            if(result) {
            
                /*
                 * Temporarily move to destination by giving false GPS signal.
                 */
                mLocationView.updateParams(new GpsParams(mDestination.getLocation()));
                if(mService != null) {
                    mService.setDestination((Destination)arg0);
                }
                mLocationView.updateDestination(mDestination);
                mPreferences.addToRecent(mDestination.getID());
            }
            else {
                Toast.makeText(this, getString(R.string.DestinationNF), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /* (non-Javadoc)
     * @see android.location.LocationListener#onProviderDisabled(java.lang.String)
     */
    public void onProviderDisabled(String provider) {
    }

    /* (non-Javadoc)
     * @see android.location.LocationListener#onProviderEnabled(java.lang.String)
     */
    public void onProviderEnabled(String provider) {
    }

    /* (non-Javadoc)
     * @see android.location.LocationListener#onStatusChanged(java.lang.String, int, android.os.Bundle)
     */
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    /**
     * 
     */
    @Override
    public void onGpsStatusChanged(int event) {
        GpsStatus gpsStatus = mLocationManager.getGpsStatus(null);
        if(GpsStatus.GPS_EVENT_STOPPED == event) {
        	mSatelliteView.updateGpsStatus(null);        	
        }
        else {
        	mSatelliteView.updateGpsStatus(gpsStatus);
        }
    }
}
