package com.example.android.fingerprintdialog;

import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component(modules = FingerprintModule.class)
public interface ObjectGraph {

    void inject(MainActivity activity);

}
