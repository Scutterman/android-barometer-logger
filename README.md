# android-barometer-logger
Uses the barometer on an Android smartphone to log atmospheric pressure / air pressure to the phone

## Summary
Automatically logs atmospheric readings from the phone's barometer at regular intervals in the background.
Opening the app displays the mean reading for each day,
as well as the highest 24-hour increase and decrease in atmospheric pressure detected on that day

## Cautions and Limitations
This app relies on the phone having a hardware barometric sensor, which is pretty standard,
but be aware that the quality, reliability, and calibration of the sensor may vary between vendors and models
and this will affect the readings it produces.

By default, this app asks the Android OS to wake it up at most once every 15 minutes so it can take a reading.
This shouldn't have a noticeable impact on battery life but this will depend on your specific make / model of phone.

## Data Collection
The app is designed to store data locally on the device, and that's it.
Once data is on the device it may become a part of standard Android processes, such as app data backup,
but those are largely beyond the control of the app and can be customised in the device's settings.
