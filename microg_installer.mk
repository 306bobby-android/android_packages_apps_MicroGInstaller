#
# Copyright (C) 2026 crDroid Android Project
#
# SPDX-License-Identifier: Apache-2.0
#

PRODUCT_PACKAGES += \
    MicroGInstaller

# Aurora Store Companion, the privileged helper that lets Aurora Store install and
# update apps without a confirmation dialog. Built only when the repository is checked
# out at packages/apps/AuroraServices, so a tree without it still builds; the setup
# wizard checks for the package at runtime and hides the option when it is absent.
ifneq ($(wildcard packages/apps/AuroraServices/Android.bp),)
PRODUCT_PACKAGES += \
    AuroraServices
endif
