#!/usr/bin/env bash

echo "Installing pre-commit git hook script to facilitate CI testing..."
ln -s ../../hooks/pre-commit .git/hooks/pre-commit
