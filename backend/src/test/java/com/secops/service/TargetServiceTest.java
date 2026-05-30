package com.secops.service;

import com.secops.dto.CreateTargetRequest;
import com.secops.dto.TargetDTO;
import com.secops.entity.Target;
import com.secops.repository.TargetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TargetServiceTest {

    @Mock
    TargetRepository targetRepository;

    @InjectMocks
    TargetService targetService;

    @Test
    void listAll_shouldReturnAllTargets() {
        Target t = new Target();
        t.setId(1L);
        t.setDomain("example.com");
        when(targetRepository.findAll()).thenReturn(List.of(t));

        List<TargetDTO> result = targetService.listAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDomain()).isEqualTo("example.com");
    }

    @Test
    void create_shouldThrow_whenDomainExists() {
        CreateTargetRequest req = new CreateTargetRequest();
        req.setDomain("example.com");
        when(targetRepository.existsByDomain("example.com")).thenReturn(true);

        assertThatThrownBy(() -> targetService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void create_shouldSaveTarget() {
        CreateTargetRequest req = new CreateTargetRequest();
        req.setDomain("example.com");
        when(targetRepository.existsByDomain("example.com")).thenReturn(false);
        when(targetRepository.save(any(Target.class))).thenAnswer(i -> i.getArgument(0));

        TargetDTO result = targetService.create(req);

        assertThat(result.getDomain()).isEqualTo("example.com");
        assertThat(result.getTxtRecord()).startsWith("secops-verify=");
    }

    @Test
    void delete_shouldThrow_whenNotFound() {
        when(targetRepository.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> targetService.delete(1L))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }

    @Test
    void verify_shouldReturnAlreadyVerified_whenTargetIsVerified() {
        Target target = new Target();
        target.setId(1L);
        target.setDomain("example.com");
        target.setVerified(true);
        when(targetRepository.findById(1L)).thenReturn(Optional.of(target));

        TargetDTO result = targetService.verify(1L);

        assertThat(result.isVerified()).isTrue();
        verify(targetRepository, never()).save(any());
    }
}
