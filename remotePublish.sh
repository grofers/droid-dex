#!/bin/bash

./gradlew clean
./gradlew droid-dex:assemble

echo -e "\n\nPublishing to PRODUCTION. BEWARE. Waiting for 5 seconds before continuing\n\n"
sleep 5

./gradlew droid-dex:publishMavenPublicationToBlinkitRepository
./gradlew droid-dex:publishAndReleaseToMavenCentral --no-configuration-cache
