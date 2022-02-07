/*
 * Copyright (C) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.opendroneid.android.app;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.annotation.Nullable;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.Manifest;
import androidx.core.app.ActivityCompat;
import androidx.viewpager2.widget.ViewPager2;

import android.content.pm.PackageManager;
import android.widget.LinearLayout;

import com.microsoft.maps.Geopath;
import com.microsoft.maps.Geopoint;
import com.microsoft.maps.Geoposition;
import com.microsoft.maps.MapAnimationKind;
import com.microsoft.maps.MapCamera;
import com.microsoft.maps.MapElement;
import com.microsoft.maps.MapElementLayer;
import com.microsoft.maps.MapElementTappedEventArgs;
import com.microsoft.maps.MapIcon;
import com.microsoft.maps.MapImage;
import com.microsoft.maps.MapPolyline;
import com.microsoft.maps.MapProjection;
import com.microsoft.maps.MapRenderMode;
import com.microsoft.maps.MapScene;
import com.microsoft.maps.MapStyleSheet;
import com.microsoft.maps.MapStyleSheets;
import com.microsoft.maps.MapTappedEventArgs;
import com.microsoft.maps.MapView;
import com.microsoft.maps.OnMapElementTappedListener;

import org.opendroneid.android.BuildConfig;
import org.opendroneid.android.R;
import org.opendroneid.android.data.AircraftObject;
import org.opendroneid.android.data.LocationData;
import org.opendroneid.android.data.SystemData;
import org.opendroneid.android.data.Util;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

public class AircraftMapView extends Fragment {
    private static final String TAG = "AircraftMapView";
    private MapView mapView;
    private MapElementLayer pinLayer;
    private MapImage markerImage;
    private MapImage markerPilotImage;
    private AircraftViewModel model;

    private final HashMap<AircraftObject, MapObserver> aircraftObservers = new HashMap<>();

    private final Util.DiffObserver<AircraftObject> allAircraftObserver = new Util.DiffObserver<AircraftObject>() {
        @Override
        public void onAdded(Collection<AircraftObject> added) {
            for (AircraftObject aircraftObject : added) {
                trackAircraft(aircraftObject);
            }
        }

        @Override
        public void onRemoved(Collection<AircraftObject> removed) {
            for (AircraftObject aircraftObject : removed) {
                stopTrackingAircraft(aircraftObject);
            }
        }
    };

    private void trackAircraft(AircraftObject aircraftObject) {
        MapObserver observer = new MapObserver(aircraftObject);
        aircraftObservers.put(aircraftObject, observer);
    }

    private void stopTrackingAircraft(AircraftObject aircraftObject) {
        MapObserver observer = aircraftObservers.remove(aircraftObject);
        if (observer == null) return;
        observer.stop();
    }

    private static final int DESIRED_ZOOM = 17;
    private static final int ALLOWED_ZOOM_MARGIN = 2;

    private void setupModel() {
        if (getActivity() == null)
            return;

        model = new ViewModelProvider(getActivity()).get(AircraftViewModel.class);
        model.getAllAircraft().observe(getViewLifecycleOwner(), allAircraftObserver);
        model.getActiveAircraft().observe(getViewLifecycleOwner(), new Observer<AircraftObject>() {
            MapObserver last = null;

            @Override
            public void onChanged(@Nullable AircraftObject object) {
                if (object == null || object.getLocation() == null)
                    return;
                MapObserver observer = aircraftObservers.get(object);
                if (observer == null)
                    return;

                if (object.getLocation().getLatitude() == 0.0 && object.getLocation().getLongitude() == 0.0)
                    return;

                Geopoint ll = new Geopoint(object.getLocation().getLatitude(), object.getLocation().getLongitude());
                Log.i(TAG, "centering on " + object + " at " + ll);

                if (last != null && last.marker != null) {
                    last.marker.setOpacity(0.5f);
                    if (last.markerPilot != null)
                        last.markerPilot.setOpacity(0.5f);
                }
                if (observer.marker != null)
                    observer.marker.setOpacity(1.0f);
                if (observer.markerPilot != null)
                    observer.markerPilot.setOpacity(1.0f);

                last = observer;

                double zoom = mapView.getZoomLevel();
                if (zoom < DESIRED_ZOOM - ALLOWED_ZOOM_MARGIN || zoom > DESIRED_ZOOM + ALLOWED_ZOOM_MARGIN)
                    zoom = DESIRED_ZOOM;
                mapView.setScene(
                        MapScene.createFromLocationAndZoomLevel(ll, zoom),
                        MapAnimationKind.NONE);
            }
        });
    }

    class MapObserver implements Observer<LocationData> {
        private MapIcon marker;
        private MapIcon markerPilot;
        //private Polyline polyline;
        //private PolylineOptions polylineOptions;

        private final AircraftObject aircraft;

        MapObserver(AircraftObject active) {
            aircraft = active;
            aircraft.location.observe(AircraftMapView.this, this);
            aircraft.system.observe(AircraftMapView.this, systemObserver);
            //polylineOptions = new PolylineOptions()
            //        .color(Color.RED)
            //        .clickable(true);
        }

        void stop() {
            aircraft.location.removeObserver(this);
            aircraft.system.removeObserver(systemObserver);
            if (marker != null) {
                pinLayer.getElements().remove(marker);
                marker = null;
            }
            if (markerPilot != null) {
                pinLayer.getElements().remove(markerPilot);
                markerPilot = null;
            }
            //if (polyline != null) {
            //    polyline.remove();
            //   polyline = null;
            //}
            //polylineOptions = null;
        }

        private final Observer<SystemData> systemObserver = new Observer<SystemData>() {
            @Override
            public void onChanged(@Nullable SystemData ignore) {
                SystemData sys = aircraft.getSystem();
                if (sys == null)
                    return;

                // filter out zero data
                if (sys.getOperatorLatitude() == 0.0 && sys.getOperatorLongitude() == 0.0)
                    return;

                Geopoint latLng = new Geopoint(sys.getOperatorLatitude(), sys.getOperatorLongitude());
                if (markerPilot == null) {
                    String id = "ID missing";
                    if (aircraft.getIdentification1() != null)
                        id = aircraft.getIdentification1().getUasIdAsString();
                    markerPilot = new MapIcon();
                    markerPilot.setTitle(sys.getOperatorLocationType().toString() + ": " + id);
                    markerPilot.setOpacity(0.5f);
                    if (markerPilotImage != null) {
                        markerPilot.setImage(markerPilotImage);
                        markerPilot.setNormalizedAnchorPoint(new PointF(0.5f, 1f));
                    }
                    markerPilot.setLocation(latLng);
                    markerPilot.setTag(new Pair<>(aircraft, this));
                    pinLayer.getElements().add(markerPilot);
                } else {
                    markerPilot.setLocation(latLng);
                }
            }
        };

        @Override
        public void onChanged(@Nullable LocationData ignore) {
            boolean zoom = false;
            LocationData loc = aircraft.getLocation();
            if (loc == null)
                return;

            // filter out zero data
            if (loc.getLatitude() == 0.0 && loc.getLongitude() == 0.0)
                return;

            Geopoint latLng = new Geopoint(loc.getLatitude(), loc.getLongitude());
            if (marker == null) {
                String id = "ID missing";
                if (aircraft.getIdentification1() != null)
                    id = aircraft.getIdentification1().getUasIdAsString();
                marker = new MapIcon();
                marker.setTitle("aircraft " + id);
                marker.setOpacity(0.5f);
                marker.setImage(markerImage);
                marker.setNormalizedAnchorPoint(new PointF(0.5f, 1f));
                marker.setLocation(latLng);
                marker.setTag(aircraft);
                pinLayer.getElements().add(marker);

                zoom = true;
            } else
                marker.setLocation(latLng);

            //polylineOptions.add(latLng);
            //if (polyline != null) {
            //    polyline.remove();
            //    polyline = null;
            //}
            //polyline = googleMap.addPolyline(polylineOptions);

            if (zoom) {
                mapView.setScene(MapScene.createFromLocation(latLng), MapAnimationKind.NONE);
            }
        }
    }

    @Override @NonNull
    public View onCreateView(@NonNull LayoutInflater layoutInflater, ViewGroup viewGroup, Bundle bundle) {
        final LinearLayout parent = (LinearLayout) layoutInflater.inflate(R.layout.fragment_aircraftmap, viewGroup, false);
        mapView = parent.findViewById(R.id.map);
        mapView.onCreate(bundle);
        mapView.setCredentialsKey(BuildConfig.CREDENTIALS_KEY);
        pinLayer = new MapElementLayer();
        pinLayer.addOnMapElementTappedListener(new OnMapElementTappedListener() {
            @Override
            public boolean onMapElementTapped(MapElementTappedEventArgs mapElementTappedEventArgs) {
                List<MapElement> elementList = mapElementTappedEventArgs.mapElements;
                for (MapElement element : elementList) {
                    Object tag = element.getTag();
                    if (tag instanceof AircraftObject) {
                        model.setActiveAircraft((AircraftObject) tag);
                        return true;
                    }
                }
                return false;
            }
        });
        mapView.getLayers().add(pinLayer);
        markerImage = getPinImage();
        return parent;
    }

    @Override
    public void onActivityCreated(Bundle bundle) {
        super.onActivityCreated(bundle);
        setupModel();
    }

    @Override
    public void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onStop() {
        super.onStop();
        mapView.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    //@Override
    //public void onMapReady(@NonNull GoogleMap googleMap) {
    //    if (getActivity() == null)
    //        return;
    //
    //    setMapSettings();
    //}

    public void setMapSettings() {
        if (getActivity() == null)
            return;

        if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        //googleMap.getUiSettings().setMyLocationButtonEnabled(true);
        //googleMap.getUiSettings().setMapToolbarEnabled(false);
        //googleMap.setMyLocationEnabled(true);
        //googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        //googleMap.setOnMarkerClickListener(this);
    }

    private MapImage getPinImage() {
        Drawable drawable = ResourcesCompat.getDrawable(getResources(), R.drawable.ic_pin, null);

        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return new MapImage(bitmap);
    }
}
