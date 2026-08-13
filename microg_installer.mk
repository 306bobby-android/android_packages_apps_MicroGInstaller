#
# Copyright (C) 2026 crDroid Android Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Aurora Store Companion is the privileged helper that lets Aurora Store install and
# update apps without a confirmation dialog. It requires the AuroraServices repository
# in the tree; a missing checkout is meant to fail the build here rather than silently
# produce a ROM whose setup wizard hides the option with no explanation.
PRODUCT_PACKAGES += \
    MicroGInstaller \
    AuroraServices
