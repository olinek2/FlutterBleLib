package com.polidea.flutterblelib;


import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.polidea.flutterblelib.exception.RxBleDeviceNotFoundException;
import com.polidea.flutterblelib.listener.OnErrorAction;
import com.polidea.flutterblelib.listener.OnSuccessAction;
import com.polidea.flutterblelib.utils.StringUtils;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.scan.ScanResult;

import rx.Observable;
import rx.Observer;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;

public class BleHelper {

    private final Converter converter;

    private final ConnectedDeviceContainer connectedDevices;

    private final ConnectingDevicesContainer connectingDevices;

    private final Context context;

    private final StringUtils stringUtils;

    private RxBleClient rxBleClient;

    private Subscription scanDevicesSubscription;

    BleHelper(Context context) {
        this.context = context;
        stringUtils = new StringUtils();
        converter = new Converter();
        connectedDevices = new ConnectedDeviceContainer();
        connectingDevices = new ConnectingDevicesContainer();
    }

    private boolean isRxBleDeviceReady(final OnErrorAction error) {
        if (rxBleClient == null) {
            error.onError(new IllegalStateException("BleManager not created when tried to start device scan"));
            return false;
        }
        return true;
    }

    void createClient() {
        rxBleClient = RxBleClient.create(context);
    }

    void destroyClient() {
        if (scanDevicesSubscription != null && !scanDevicesSubscription.isUnsubscribed()) {
            scanDevicesSubscription.unsubscribe();
        }
        scanDevicesSubscription = null;

        connectedDevices.clear();

        rxBleClient = null;
    }

    void startDeviceScan(byte[] scanSettingsBytes,
                         final OnSuccessAction<BleData.ScanResultMessage> success,
                         final OnErrorAction error) {
        if (!isRxBleDeviceReady(error)) {
            return;
        }
        scanDevicesSubscription = rxBleClient
                .scanBleDevices(converter.convertToScanSettings(scanSettingsBytes))
                .subscribe(
                        new Action1<ScanResult>() {
                            @Override
                            public void call(ScanResult scanResult) {
                                success.onSuccess(converter.convertToScanResultMessage(scanResult));
                            }
                        },
                        new Action1<Throwable>() {
                            @Override
                            public void call(Throwable throwable) {
                                error.onError(throwable);
                            }
                        }
                );
    }

    void stopDeviceScan() {
        if (scanDevicesSubscription != null && !scanDevicesSubscription.isUnsubscribed()) {
            scanDevicesSubscription.unsubscribe();
        }
        scanDevicesSubscription = null;
    }


    void connectToDevice(byte[] connectToDeviceDataMessageByte,
                         final OnSuccessAction<BleData.ConnectedDeviceMessage> success,
                         final OnErrorAction error) {

        if (!isRxBleDeviceReady(error)) {
            return;
        }
        final BleData.ConnectToDeviceDataMessage connectToDeviceDataMessage
                = converter.convertToConnectToDeviceDataMessage(connectToDeviceDataMessageByte);
        if (connectToDeviceDataMessage == null) {
            error.onError(new IllegalArgumentException("scanResultByte argument contains wrong data"));
            return;
        }
        final String macAddress = connectToDeviceDataMessage.getMacAddress();
        final RxBleDevice rxBleDevice = rxBleClient.getBleDevice(macAddress);
        if (rxBleDevice == null) {
            error.onError(new RxBleDeviceNotFoundException("Not found device for mac address : " + macAddress));
            return;
        }
        final boolean isAutoConnect = connectToDeviceDataMessage.getIsAutoConnect();
        final int requestMtu = connectToDeviceDataMessage.getRequestMtu();
        saveConnectToDevice(rxBleDevice, isAutoConnect, requestMtu, success, error);
    }

    private void saveConnectToDevice(final RxBleDevice device, boolean autoConnect, final int requestMtu,
                                     final OnSuccessAction<BleData.ConnectedDeviceMessage> success, final OnErrorAction error) {
        Observable<RxBleConnection> connect = device
                .establishConnection(autoConnect)
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        //TODO close connection, device disconnected
                    }
                });

        if (requestMtu > 0) {
            connect = connect.flatMap(new Func1<RxBleConnection, Observable<RxBleConnection>>() {
                @Override
                public Observable<RxBleConnection> call(final RxBleConnection rxBleConnection) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        return rxBleConnection
                                .requestMtu(requestMtu)
                                .map(new Func1<Integer, RxBleConnection>() {
                                    @Override
                                    public RxBleConnection call(Integer integer) {
                                        return rxBleConnection;
                                    }
                                });
                    } else {
                        return Observable.just(rxBleConnection);
                    }
                }
            });
        }

        final Subscription subscription = connect
                .subscribe(new Observer<RxBleConnection>() {
                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void onError(Throwable e) {
                        error.onError(e);
                        //TODO device disconnected
                    }

                    @Override
                    public void onNext(RxBleConnection connection) {
                        BleData.ConnectedDeviceMessage connectedDeviceMessage = BleData.ConnectedDeviceMessage.newBuilder()
                                .setDeviceMessage(BleData.BleDeviceMessage.newBuilder()
                                        .setMacAddress(stringUtils.safeNullInstance(device.getMacAddress()))
                                        .setName(stringUtils.safeNullInstance(device.getName()))
                                )
                                .setMtu(connection.getMtu())
                                .setRssi(-1)
                                .build();
                        connectedDevices.put(device.getMacAddress(), connectedDeviceMessage);
                        success.onSuccess(connectedDeviceMessage);
                    }
                });

        connectingDevices.replaceConnectingSubscription(device.getMacAddress(), subscription);
    }
}