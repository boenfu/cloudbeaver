/*
 * cloudbeaver - Cloud Database Manager
 * Copyright (C) 2020 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

import { observer } from 'mobx-react';
import { useCallback } from 'react';
import styled, { css } from 'reshadow';

import { InlineEditor } from '@dbeaver/core/app';
import { InputField, IconButton, SubmittingForm } from '@dbeaver/core/blocks';
import { useTranslate } from '@dbeaver/core/localization';
import { composes, useStyles } from '@dbeaver/core/theming';

import { TableViewerModel } from '../TableViewerModel';

const styles = composes(
  css`
    InlineEditor {
      composes: theme-background-surface theme-text-on-surface from global;
    }
  `,
  css`
    SubmittingForm {
      height: 40px;
      flex: 1;
      display: flex;
      align-items: center;
    }
    InlineEditor {
      flex: 1;
      height: 24px;
      margin: 0 12px;
    }
  `
);

type Props = {
  context: TableViewerModel;
}

export const TableWhereFilter = observer(function TableWhereFilter({
  context,
}: Props) {
  const translate = useTranslate();
  const handleChange = useCallback(
    (value: string) => context.setQueryWhereFilter(value),
    [context]
  );
  const resetFilter = useCallback(
    () => {
      context.setQueryWhereFilter('');
      context.applyQueryFilters();
    },
    [context]
  );

  return styled(useStyles(styles))(
    <SubmittingForm onSubmit={() => context.applyQueryFilters()}>
      <InlineEditor
        value={context.getQueryWhereFilter() || ''}
        onSave={() => context.applyQueryFilters()}
        onReject={resetFilter}
        onChange={handleChange}
        placeholder={translate('table_header_sql_expression')}
        controlsPosition='inside'
        simple
      />
    </SubmittingForm>
  );
});
