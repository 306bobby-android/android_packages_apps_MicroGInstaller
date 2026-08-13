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

# Both apps are privileged, and a privileged app requesting a privileged permission
# that is not in an installed allowlist is a fatal error at boot rather than a runtime
# denial. The app modules already pull these in via required:, but naming them here as
# well means a missing allowlist can never be the reason a device fails to boot.
PRODUCT_PACKAGES += \
    privapp-permissions-org.microg.installer \
    privapp-permissions-com.aurora.services
