#!/usr/bin/env python3
"""Package and verify a clean committed synthetic candidate; preserve older packages."""
from release_tools import package_release

if __name__ == '__main__':
    try:
        print('Verified synthetic release candidate:', package_release())
    except (ValueError, OSError) as error:
        raise SystemExit(str(error)) from None
