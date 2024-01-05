#!/bin/bash

./gradlew clean
./gradlew droid-dex:assembleRelease

echo -e "\n\nPublishing to LOCAL. BEWARE. Waiting for 2 seconds before continuing\n\n"
sleep 2

./gradlew droid-dex:publishMavenPublicationToMavenLocal
